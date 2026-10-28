package com.alssanbox.mousehole

import android.Manifest
import android.content.ContentValues
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }
    override fun getRootView(layoutInflater: LayoutInflater): View {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xff101216.toInt()) }
        status = TextView(this).apply { text = "Mode: ${modeDescription()} — connect USB-C endoscope"; setTextColor(-1); setPadding(18,18,18,18) }
        root.addView(status)
        preview = AspectRatioTextureView(this)
        previewFrame = FrameLayout(this)
        root.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        fun btn(text: String, action: () -> Unit): Button = Button(this).apply { this.text = text; setOnClickListener { action() }; controls.addView(this) }
        btn("PHOTO") { if (isCameraOpened()) captureImage(callback, outputFile(false).absolutePath) else toast("Connect a camera first") }
        recordButton = btn("RECORD") { toggleRecord() }
        btn("USB INFO") { showUsbDevices() }
        btn("SIZES") { showSizes() }
        root.addView(controls)
        // Keep the diagnostic mode control visible on narrower devices such as the Galaxy A16.
        root.addView(Button(this).apply {
            text = "CHANGE CAMERA MODE (${modeDescription()})"
            setOnClickListener { nextMode() }
        })
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
            captureVideoStop(); recording = false; recordButton.text = "RECORD"
            status.text = "Finishing MP4..."
        } else {
            if (!isCameraOpened()) { toast("No USB camera open"); return }
            // Video without audio is allowed. Grant RECORD_AUDIO before connecting to include mic sound.
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast("Recording without audio. Grant microphone permission in Settings for sound.")
            }
            currentVideo = outputFile(true)
            captureVideoStart(callback, currentVideo!!.absolutePath)
            recording = true; recordButton.text = "STOP"; status.text = "Recording"
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
        status.text = if (sizes.isNullOrEmpty()) "Camera has not reported resolutions" else sizes.joinToString("\n") { it.toString() }
    }
    private fun showUsbDevices() {
        val list=(getSystemService(USB_SERVICE) as UsbManager).deviceList.values
        status.text=if(list.isEmpty()) "No USB devices detected" else list.joinToString("\n") { "VID ${it.vendorId} PID ${it.productId} ${it.deviceName}" }
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    override fun onPause() {
        if(recording) { captureVideoStop();recording=false;recordButton.text="RECORD" }
        super.onPause()
    }
}
