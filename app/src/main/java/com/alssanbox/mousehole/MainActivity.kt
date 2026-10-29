package com.alssanbox.mousehole

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.MultiCameraClient.ICamera
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.File

/** UVC prototype. Opens USB device using AUSBC auto-permission workflow. */
class MainActivity : CameraActivity() {
    private lateinit var root: LinearLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var preview: AspectRatioTextureView
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private lateinit var controlsPanel: LinearLayout
    private lateinit var refreshButton: Button
    private lateinit var shutterButton: Button
    private var videoMode = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordStartedAt = 0L
    private val ticker = object : Runnable {
        override fun run() {
            if (!recording) return
            val secs = (android.os.SystemClock.elapsedRealtime() - recordStartedAt) / 1000
            status.text = "● REC  %02d:%02d  •  %s".format(secs / 60, secs % 60, modeDescription())
            timerHandler.postDelayed(this, 1000)
        }
    }
    private var recording = false
    private var currentVideo: File? = null
    // The supplied endoscope works in YUYV; never request an unsupported MJPEG mode.
    private fun modeDescription() = "640x480 YUYV"
    private val usbManager by lazy { getSystemService(USB_SERVICE) as UsbManager }
    private var receiverRegistered = false
    private var lastReconnectAt = 0L
    private var reconnectPending = false
    private var activityStopping = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectAction = Runnable {
        reconnectPending = false
        if (activityStopping || isFinishing || isDestroyed) return@Runnable
        if (usbManager.deviceList.isEmpty()) {
            if (::status.isInitialized) status.text = "USB disconnected • Connect your endoscope"
            return@Runnable
        }
        // A new Activity gives CameraActivity/AUSBC a clean monitor and camera session.
        if (recording) stopRecordingForDisconnect()
        lastReconnectAt = SystemClock.elapsedRealtime()
        recreate()
    }
    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (recording) stopRecordingForDisconnect()
                    if (::status.isInitialized) status.text = "USB disconnected • Waiting to reconnect"
                    // A pending reconnect must not run against a detached device.
                    reconnectHandler.removeCallbacks(reconnectAction)
                    reconnectPending = false
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scheduleReconnect(1000L)
            }
        }
    }
    private fun stopRecordingForDisconnect() {
        if (!recording) return
        recording = false
        timerHandler.removeCallbacks(ticker)
        // AUSBC may have already torn down its native capture on detach.
        try { captureVideoStop() } catch (error: Exception) {
            Log.w("MouseHoleUVC", "Could not finalize recording after USB disconnect", error)
        }
        refreshShutter()
    }
    private fun scheduleReconnect(delayMillis: Long = 650L) {
        if (activityStopping || isFinishing || isDestroyed) return
        val elapsed = SystemClock.elapsedRealtime() - lastReconnectAt
        if (elapsed in 0..1500 && reconnectPending) return
        reconnectHandler.removeCallbacks(reconnectAction)
        reconnectPending = true
        if (::status.isInitialized) status.text = "Reconnecting USB camera..."
        reconnectHandler.postDelayed(reconnectAction, delayMillis)
    }

    private val callback = object : ICaptureCallBack {
        override fun onBegin() { runOnUiThread { status.text = "Capture started" } }
        override fun onError(error: String?) { runOnUiThread { status.text = "Capture error: ${error ?: "unknown"}" } }
        override fun onComplete(path: String?) {
            runOnUiThread {
                if (path != null) {
                    val f = File(path)
                    if (f.isFile) {
                        // MediaStore copy exposes a shareable file in Gallery, with no broad storage permission.
                        val video = f.extension.equals("mp4", true)
                        saveToGallery(f, video)
                    }
                }
                status.text = "Capture complete"
            }
        }
    }


    private val navy = Color.rgb(5, 13, 26)
    private val cyan = Color.rgb(0, 190, 255)

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun rounded(color: Int, stroke: Int = Color.TRANSPARENT, radius: Int = 18): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun immersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbEvents, filter)
        receiverRegistered = true
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    private fun smallButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(13, 29, 49), cyan, 15)
            setPadding(dp(5), 0, dp(5), 0)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { action() }
        }

    private fun showTools() {
        val items = arrayOf(
            "USB devices", "Reported preview sizes", "Reconnect camera",
            "Microphone information"
        )
        AlertDialog.Builder(this)
            .setTitle("Mouse Hole • Camera tools")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showUsbDevices()
                    1 -> showSizes()
                    2 -> scheduleReconnect(200L)
                    3 -> showDetails("Microphone", "Optional audio requires microphone permission before connecting the camera. Without it, MP4 recording is silent.")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDetails(title: String, details: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(details)
            .setPositiveButton("OK", null).show()
    }

    private fun capturePhoto() {
        if (isCameraOpened()) captureImage(callback, outputFile(false).absolutePath)
        else toast("Connect a USB camera first")
    }

    private fun refreshShutter() {
        if (!::shutterButton.isInitialized) return
        shutterButton.text = if (recording) "■ STOP" else if (videoMode) "● REC" else "● PHOTO"
        shutterButton.setTextColor(if (recording) Color.WHITE else if (videoMode) Color.rgb(255, 85, 90) else navy)
        shutterButton.background = rounded(
            if (recording) Color.rgb(202, 35, 55) else Color.WHITE,
            if (videoMode) Color.rgb(255, 69, 84) else cyan, 35
        )
        if (::recordButton.isInitialized) {
            recordButton.background = rounded(if (videoMode) Color.rgb(0, 61, 95) else Color.rgb(13, 29, 49),
                if (videoMode) cyan else Color.TRANSPARENT)
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) }
        catch (e: Exception) { toast("No gallery app available") }
    }


    override fun getRootView(layoutInflater: LayoutInflater): View {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val margin = if (landscape) 6 else 10
        val headerHeight = if (landscape) 35 else 47
        val tabHeight = if (landscape) 34 else 46
        val shutterHeight = if (landscape) 42 else 66
        val shutterWidth = if (landscape) 94 else 130

        // Full-window camera layer; all controls float over the live picture.
        val screen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = AspectRatioTextureView(this)
        previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        screen.addView(previewFrame, FrameLayout.LayoutParams(-1, -1))

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(margin), dp(margin), dp(margin), dp(margin))
        }
        screen.addView(root, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(188, 5, 13, 26), cyan, 16)
            setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(TextView(this).apply {
            text = "Mouse Hole  •  YUYV"
            setTextColor(cyan)
            setTypeface(null, Typeface.BOLD)
            textSize = if (landscape) 13f else 19f
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -1, 1f))
        refreshButton = smallButton("↻  Refresh") { scheduleReconnect(150L) }.apply {
            textSize = if (landscape) 10f else 11f
        }
        header.addView(refreshButton, LinearLayout.LayoutParams(dp(if (landscape) 80 else 96), dp(headerHeight - 7)))
        header.addView(smallButton("⚙") { showTools() }, LinearLayout.LayoutParams(dp(if (landscape) 40 else 48), dp(headerHeight - 7)).apply {
            marginStart = dp(5)
        })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(headerHeight)))

        status = TextView(this).apply {
            text = "USB disconnected  •  ${modeDescription()}"
            setTextColor(Color.WHITE)
            textSize = if (landscape) 10f else 12f
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(176, 5, 13, 26), radius = 9)
            setPadding(dp(8), 0, dp(8), 0)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(if (landscape) 23 else 32)).apply {
            topMargin = dp(3)
        })

        // This flexible spacer keeps controls on the bottom without shrinking the video surface.
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        controlsPanel = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(Color.argb(202, 5, 16, 32), cyan, 18)
            setPadding(dp(if (landscape) 7 else 10), dp(if (landscape) 4 else 9),
                dp(if (landscape) 7 else 10), dp(if (landscape) 4 else 9))
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val photoTab = smallButton("Photo") {
            if (!recording) { videoMode = false; refreshShutter() }
        }
        recordButton = smallButton("Video") {
            if (!recording) { videoMode = true; refreshShutter() }
        }
        val galleryTab = smallButton("Gallery") { openGallery() }
        for (tab in listOf(photoTab, recordButton, galleryTab)) {
            tab.textSize = if (landscape) 10f else 11f
            tabs.addView(tab, LinearLayout.LayoutParams(0, dp(tabHeight), 1f).apply {
                marginStart = dp(2); marginEnd = dp(2)
            })
        }
        if (landscape) {
            controlsPanel.addView(tabs, LinearLayout.LayoutParams(0, dp(tabHeight), 1f))
        } else {
            controlsPanel.addView(tabs, LinearLayout.LayoutParams(-1, dp(tabHeight)))
        }
        shutterButton = Button(this).apply {
            isAllCaps = false
            textSize = if (landscape) 12f else 17f
            setTypeface(null, Typeface.BOLD)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { if (recording || videoMode) toggleRecord() else capturePhoto() }
        }
        if (landscape) {
            controlsPanel.addView(shutterButton, LinearLayout.LayoutParams(dp(shutterWidth), dp(shutterHeight)).apply {
                marginStart = dp(8)
            })
            root.addView(controlsPanel, LinearLayout.LayoutParams(-1, dp(53)))
        } else {
            controlsPanel.addView(shutterButton, LinearLayout.LayoutParams(dp(shutterWidth), dp(shutterHeight)).apply {
                topMargin = dp(7)
            })
            root.addView(controlsPanel, LinearLayout.LayoutParams(-1, dp(143)))
        }
        refreshShutter()
        return screen
    }
    override fun getCameraView(): IAspectRatio? = preview
    override fun getCameraViewContainer(): ViewGroup? = previewFrame
    override fun getGravity(): Int = Gravity.CENTER
    override fun onCameraState(self: ICamera, code: ICameraStateCallBack.State, msg: String?) {
        Log.i("MouseHoleUVC", "State=$code mode=${modeDescription()} details=${msg ?: ""}")
        runOnUiThread {
            status.text = "${modeDescription()} — USB camera: $code ${msg ?: ""}"
        }
    }
    override fun getCameraRequest(): CameraRequest {
        val w = 640
        val h = 480
        val format = CameraRequest.PreviewFormat.FORMAT_YUYV
        Log.i("MouseHoleUVC", "Opening requested mode ${modeDescription()}")
        return CameraRequest.Builder()
        .setPreviewWidth(w).setPreviewHeight(h)
        .setRenderMode(CameraRequest.RenderMode.OPENGL)
        .setDefaultRotateType(RotateType.ANGLE_0)
        .setAudioSource(if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
            CameraRequest.AudioSource.SOURCE_SYS_MIC else CameraRequest.AudioSource.NONE)
        .setPreviewFormat(format)
        .create()
    }
    private fun toggleRecord() {
        if (recording) {
            captureVideoStop(); recording = false; timerHandler.removeCallbacks(ticker); refreshShutter()
            status.text = "Finishing MP4..."
        } else {
            if (!isCameraOpened()) { toast("No USB camera open"); return }
            // Video without audio is allowed. Grant RECORD_AUDIO before connecting to include mic sound.
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast("Recording without audio. Grant microphone permission in Settings for sound.")
            }
            currentVideo = outputFile(true)
            captureVideoStart(callback, currentVideo!!.absolutePath)
            recording = true; recordStartedAt = android.os.SystemClock.elapsedRealtime(); refreshShutter(); timerHandler.post(ticker)
        }
    }
    private fun outputFile(video:Boolean): File {
        val folder = getExternalFilesDir(if(video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)!!
        folder.mkdirs()
        return File(folder, "MouseHole_${System.currentTimeMillis()}.${if(video) "mp4" else "jpg"}")
    }
    private fun saveToGallery(f:File, video:Boolean) {
        try {
            val media = if(video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if(video) "video/mp4" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, if(video) "Movies/MouseHole" else "Pictures/MouseHole")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(media,values) ?: error("MediaStore insert failed")
            try { contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: error("No output stream")
                values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);contentResolver.update(uri,values,null,null)
                status.text="Saved: ${f.name}"
            } catch(e:Exception) { contentResolver.delete(uri,null,null);throw e }
        } catch(e:Exception) { status.text="Local capture saved; Gallery copy failed: ${e.message}" }
    }
    private fun showSizes() {
        val sizes = getAllPreviewSizes()
        showDetails("Reported preview sizes", if (sizes.isNullOrEmpty()) "Camera has not reported resolutions" else sizes.joinToString("\n") { it.toString() })
    }
    private fun showUsbDevices() {
        val list=(getSystemService(USB_SERVICE) as UsbManager).deviceList.values
        showDetails("USB devices", if(list.isEmpty()) "No USB devices detected" else list.joinToString("\n") { "VID ${it.vendorId} PID ${it.productId} ${it.deviceName}" })
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    override fun onDestroy() {
        activityStopping = true
        reconnectHandler.removeCallbacks(reconnectAction)
        if (receiverRegistered) {
            try { unregisterReceiver(usbEvents) } catch (_: IllegalArgumentException) { }
            receiverRegistered = false
        }
        super.onDestroy()
    }
    override fun onPause() {
        if(recording) { captureVideoStop();recording=false;timerHandler.removeCallbacks(ticker);refreshShutter() }
        super.onPause()
    }
}
