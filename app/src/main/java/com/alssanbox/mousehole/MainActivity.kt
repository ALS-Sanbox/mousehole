package com.alssanbox.mousehole

import android.Manifest
import android.content.ContentValues
import android.content.Intent
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
    private val modes = listOf(
        Triple(640, 480, CameraRequest.PreviewFormat.FORMAT_MJPEG),
        Triple(640, 480, CameraRequest.PreviewFormat.FORMAT_YUYV),
        Triple(1280, 720, CameraRequest.PreviewFormat.FORMAT_MJPEG)
    )
    private val modeIndex: Int
        get() = getSharedPreferences("camera", MODE_PRIVATE).getInt("modeIndex", 0).coerceIn(modes.indices)
    private fun modeDescription(): String {
        val (w, h, format) = modes[modeIndex]
        return "${w}x${h} ${if (format == CameraRequest.PreviewFormat.FORMAT_MJPEG) "MJPEG" else "YUYV"}"
    }
    private fun nextMode() {
        if (recording) { toast("Stop recording before changing mode"); return }
        getSharedPreferences("camera", MODE_PRIVATE).edit()
            .putInt("modeIndex", (modeIndex + 1) % modes.size).apply()
        toast("Next mode: ${modeDescription()}. Restarting USB preview")
        // CameraActivity unregisters and re-registers USB monitor during Activity lifecycle.
        recreate()
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
            "USB devices", "Reported preview sizes", "Change camera mode (${modeDescription()})",
            "Microphone information"
        )
        AlertDialog.Builder(this)
            .setTitle("Mouse Hole • Camera tools")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showUsbDevices()
                    1 -> showSizes()
                    2 -> nextMode()
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
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
            // Insets are handled by immersive transient bars; controls stay in the app's layout.
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Mouse Hole"
            setTextColor(cyan)
            setTypeface(null, Typeface.BOLD)
            textSize = 23f
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(smallButton("⚙  TOOLS") { showTools() },
            LinearLayout.LayoutParams(dp(100), dp(42)))
        root.addView(header)

        status = TextView(this).apply {
            text = "USB disconnected  •  ${modeDescription()}"
            setTextColor(Color.rgb(180, 210, 231))
            textSize = 12f
            maxLines = 2
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(5))
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(39)))

        preview = AspectRatioTextureView(this)
        previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        root.addView(previewFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.rgb(5, 16, 32), cyan, 25)
            setPadding(dp(10), dp(10), dp(10), dp(12))
        }
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val photoTab = smallButton("▣  Photo") {
            if (!recording) { videoMode = false; refreshShutter() }
        }
        recordButton = smallButton("▣  Video") {
            if (!recording) { videoMode = true; refreshShutter() }
        }
        val galleryTab = smallButton("▧  Gallery") { openGallery() }
        for (tab in listOf(photoTab, recordButton, galleryTab)) {
            tabs.addView(tab, LinearLayout.LayoutParams(0, dp(49), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        panel.addView(tabs)
        shutterButton = Button(this).apply {
            isAllCaps = false
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            minHeight = 0
            minimumHeight = 0
            setOnClickListener { if (recording || videoMode) toggleRecord() else capturePhoto() }
        }
        panel.addView(shutterButton, LinearLayout.LayoutParams(dp(138), dp(74)).apply {
            topMargin = dp(12)
        })
        root.addView(panel, LinearLayout.LayoutParams(-1, dp(162)).apply {
            topMargin = dp(8)
        })
        refreshShutter()
        return root
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
        val (w, h, format) = modes[modeIndex]
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
    override fun onPause() {
        if(recording) { captureVideoStop();recording=false;timerHandler.removeCallbacks(ticker);refreshShutter() }
        super.onPause()
    }
}
