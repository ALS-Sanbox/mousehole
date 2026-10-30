package com.alssanbox.mousehole

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.app.AlertDialog
import android.graphics.Color
import android.content.res.ColorStateList
import android.view.ScaleGestureDetector
import android.widget.ImageButton

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
    private lateinit var controlsPanel: LinearLayout
    private lateinit var controlRail: LinearLayout
    private lateinit var shutterButton: ImageButton
    private lateinit var photoButton: ImageButton
    private lateinit var videoButton: ImageButton
    private var mirrorHorizontal = false
    private var mirrorVertical = false
    private var previewRotation = 0
    private var didScaleGesture = false
    private val pinchDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                didScaleGesture = true
                zoomLevel = (zoomLevel * detector.scaleFactor).coerceIn(1f, 4f)
                applyPreviewCrop()
                showOverlay()
                return true
            }
        })
    }
    private fun rotatePreview() {
        previewRotation = (previewRotation + 90) % 360
        applyPreviewCrop()
        showOverlay()
    }
    private fun flipPreview() {
        mirrorHorizontal = !mirrorHorizontal
        applyPreviewCrop()
        showOverlay()
    }
    private fun flipPreviewVertical() {
        mirrorVertical = !mirrorVertical
        applyPreviewCrop()
        showOverlay()
    }
    private fun iconButton(icon: Int, hint: String, action: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = hint
            background = rounded(Color.argb(194, 5, 17, 33), cyan, 15)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { showOverlay(); action() }
        }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var uiVisible = true
    private var zoomLevel = 1.0f
    private val hideUiRunnable = Runnable {
        if (!isFinishing && !isDestroyed && ::root.isInitialized) {
            root.visibility = View.GONE
            if (::controlRail.isInitialized) controlRail.visibility = View.GONE
            uiVisible = false
        }
    }
    private fun showOverlay() {
        if (!::root.isInitialized) return
        root.visibility = View.VISIBLE
        if (::controlRail.isInitialized) controlRail.visibility = View.VISIBLE
        uiVisible = true
        uiHandler.removeCallbacks(hideUiRunnable)
        uiHandler.postDelayed(hideUiRunnable, 4500L)
    }
    private fun toggleOverlay() {
        if (uiVisible) {
            uiHandler.removeCallbacks(hideUiRunnable)
            root.visibility = View.GONE
            if (::controlRail.isInitialized) controlRail.visibility = View.GONE
            uiVisible = false
        } else showOverlay()
    }
    private fun applyPreviewCrop() {
        if (!::preview.isInitialized || !::previewFrame.isInitialized) return
        preview.post {
            if (preview.width <= 0 || preview.height <= 0 || previewFrame.width <= 0 || previewFrame.height <= 0) return@post
            val ratioX = previewFrame.width.toFloat() / preview.width
            val ratioY = previewFrame.height.toFloat() / preview.height
            // Rotation changes the visible bounding box: swap width and height at 90/270.
            val quarterTurn = previewRotation % 180 != 0
            val displayedWidth = if (quarterTurn) preview.height.toFloat() else preview.width.toFloat()
            val displayedHeight = if (quarterTurn) preview.width.toFloat() else preview.height.toFloat()
            val ratioRotX = previewFrame.width.toFloat() / displayedWidth
            val ratioRotY = previewFrame.height.toFloat() / displayedHeight
            val scale = (if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                minOf(ratioRotX, ratioRotY) else maxOf(ratioRotX, ratioRotY)) * zoomLevel
            preview.pivotX = preview.width / 2f
            preview.pivotY = preview.height / 2f
            preview.rotation = previewRotation.toFloat()
            preview.scaleX = scale * (if (mirrorHorizontal) -1f else 1f)
            preview.scaleY = scale * (if (mirrorVertical) -1f else 1f)
        }
    }
    private fun changeZoom(delta: Float) {
        zoomLevel = (zoomLevel + delta).coerceIn(1f, 3f)
        applyPreviewCrop()
        showOverlay()
    }
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
    private val orientationPrefs by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    private val portraitUi: Boolean get() = orientationPrefs.getBoolean("portraitUi", false)
    private fun switchUiOrientation() {
        if (recording) { toast("Stop recording before changing layout"); return }
        val newPortrait = !portraitUi
        orientationPrefs.edit().putBoolean("portraitUi", newPortrait).apply()
        requestedOrientation = if (newPortrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
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
        if (hasFocus) { immersive(); showOverlay() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences("camera", MODE_PRIVATE).edit().remove("tryHdYuyv").apply()
        val requested = if (portraitUi) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val actualPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (actualPortrait != portraitUi) requestedOrientation = requested
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
            setOnClickListener { showOverlay(); action() }
        }

    private fun showTools() {
        val items = arrayOf(
            "USB devices", "Reported preview sizes", "Reconnect camera",
            "Switch portrait / landscape UI", "Microphone information"
        )
        AlertDialog.Builder(this)
            .setTitle("Mouse Hole • Camera tools")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showUsbDevices()
                    1 -> showSizes()
                    2 -> scheduleReconnect(200L)
                    3 -> switchUiOrientation()
                    4 -> showDetails("Microphone", "Optional audio requires microphone permission before connecting the camera. Without it, MP4 recording is silent.")
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
        val red = Color.rgb(238, 61, 75)
        shutterButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (recording) red else Color.WHITE)
            setStroke(dp(3), cyan)
        }
        shutterButton.setImageResource(
            if (recording) android.R.drawable.ic_media_pause
            else if (videoMode) android.R.drawable.presence_video_online
            else android.R.drawable.ic_menu_camera
        )
        shutterButton.imageTintList = ColorStateList.valueOf(if (recording) Color.WHITE else if (videoMode) red else navy)
        shutterButton.contentDescription = if (recording) "Stop video recording" else if (videoMode) "Start video recording" else "Take photo"
        if (::photoButton.isInitialized) {
            photoButton.background = rounded(
                if (!videoMode) Color.argb(232, 4, 70, 105) else Color.argb(194, 5, 17, 33),
                if (!videoMode) cyan else Color.TRANSPARENT)
        }
        if (::videoButton.isInitialized) {
            videoButton.background = rounded(
                if (videoMode) Color.argb(232, 4, 70, 105) else Color.argb(194, 5, 17, 33),
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
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val screen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = true }
        preview = AspectRatioTextureView(this)
        previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
            setOnClickListener { toggleOverlay() }
        }
        screen.addView(previewFrame, FrameLayout.LayoutParams(-1, -1))
        previewFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPreviewCrop() }
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPreviewCrop() }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (isPortrait) 12 else 10), dp(6), dp(if (isPortrait) 12 else 10), dp(7))
            setOnClickListener { toggleOverlay() }
        }
        screen.addView(root, FrameLayout.LayoutParams(-1, -1))

        // Let a single touch reveal/hide the UI, or two fingers pinch to zoom.
        preview.setOnTouchListener { view, event ->
            pinchDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { didScaleGesture = false; true }
                MotionEvent.ACTION_POINTER_DOWN -> { didScaleGesture = true; true }
                MotionEvent.ACTION_UP -> {
                    if (!didScaleGesture) toggleOverlay()
                    view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        header.addView(TextView(this).apply {
            text = "Mouse Hole"
            textSize = if (isPortrait) 15f else 17f
            setTextColor(cyan)
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }, LinearLayout.LayoutParams(0, dp(39), 1f))
        status = TextView(this).apply {
            text = "USB • ${modeDescription()}"
            maxLines = 1
            textSize = 10f
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(170, 5, 13, 26), radius = 12)
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
        }
        header.addView(status, LinearLayout.LayoutParams(0, dp(30), 1.2f))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(40)))
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        // Small vertical icon rail keeps camera controls off the picture.
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = rounded(Color.argb(100, 5, 13, 26), radius = 18)
        }
        fun railItem(icon: Int, hint: String, action: () -> Unit) {
            rail.addView(iconButton(icon, hint, action),
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { bottomMargin = dp(5) })
        }
        railItem(android.R.drawable.ic_menu_rotate, "Rotate preview 90 degrees") { rotatePreview() }
        railItem(android.R.drawable.ic_menu_revert, "Flip preview left to right") { flipPreview() }
        railItem(android.R.drawable.ic_menu_sort_by_size, "Flip preview top to bottom") { flipPreviewVertical() }
        railItem(android.R.drawable.ic_menu_crop, "Change portrait or landscape controls") { switchUiOrientation() }
        railItem(android.R.drawable.ic_menu_gallery, "Open gallery") { openGallery() }
        railItem(android.R.drawable.ic_popup_sync, "Reconnect USB camera") { scheduleReconnect(150L) }
        railItem(android.R.drawable.ic_menu_manage, "USB and camera settings") { showTools() }
        // An independent rail overlay floats at the screen edge, never consuming preview layout.
        screen.addView(rail, FrameLayout.LayoutParams(dp(48), -2,
            Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(8) })
        // Rail is part of the controls: hide and reveal it together with the header and shutter.
        controlRail = rail

        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.argb(150, 5, 13, 26), radius = 28)
            setPadding(dp(7), dp(5), dp(7), dp(5))
        }
        val controlSize = if (isPortrait) 55 else 46
        val shutterSize = if (isPortrait) 76 else 58
        photoButton = iconButton(android.R.drawable.ic_menu_camera, "Photo mode") {
            if (!recording) { videoMode = false; refreshShutter() }
        }
        videoButton = iconButton(android.R.drawable.presence_video_online, "Video mode") {
            if (!recording) { videoMode = true; refreshShutter() }
        }
        shutterButton = ImageButton(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(15), dp(15), dp(15), dp(15))
            setOnClickListener {
                showOverlay()
                if (recording || videoMode) toggleRecord() else capturePhoto()
            }
        }
        bottom.addView(photoButton, LinearLayout.LayoutParams(dp(controlSize), dp(controlSize)))
        bottom.addView(shutterButton, LinearLayout.LayoutParams(dp(shutterSize), dp(shutterSize)).apply {
            marginStart = dp(if (isPortrait) 18 else 22)
            marginEnd = dp(if (isPortrait) 18 else 22)
        })
        bottom.addView(videoButton, LinearLayout.LayoutParams(dp(controlSize), dp(controlSize)))
        controlsPanel = bottom
        val bottomHeight = shutterSize + 12
        root.addView(bottom, LinearLayout.LayoutParams(-2, dp(bottomHeight)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        refreshShutter()
        showOverlay()
        return screen
    }
    override fun getCameraView(): IAspectRatio? = preview
    override fun getCameraViewContainer(): ViewGroup? = previewFrame
    override fun getGravity(): Int = Gravity.CENTER
    override fun onCameraState(self: ICamera, code: ICameraStateCallBack.State, msg: String?) {
        Log.i("MouseHoleUVC", "State=$code mode=${modeDescription()} details=${msg ?: ""}")
        runOnUiThread {
            if (!::status.isInitialized) return@runOnUiThread
            status.text = "${modeDescription()} • USB: $code ${msg ?: ""}"
            if (code.toString().contains("OPENED", true)) applyPreviewCrop()
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
        uiHandler.removeCallbacks(hideUiRunnable)
        reconnectHandler.removeCallbacks(reconnectAction)
        if (receiverRegistered) {
            try { unregisterReceiver(usbEvents) } catch (_: IllegalArgumentException) { }
            receiverRegistered = false
        }
        super.onDestroy()
    }
    override fun onPause() {
        uiHandler.removeCallbacks(hideUiRunnable)
        if(recording) { captureVideoStop();recording=false;timerHandler.removeCallbacks(ticker);refreshShutter() }
        super.onPause()
    }
}
