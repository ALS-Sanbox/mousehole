# Mouse Hole 🐭

**USB-C endoscope camera app for Android**

![Mouse Hole logo](mouse-hole-logo.png)

Mouse Hole is an experimental, open-source Android application for viewing and capturing footage from compatible USB Video Class (UVC) endoscope cameras. Developed by **Afro Lion Studios, LLC**, with assistance from artificial intelligence (AI).

No account, Wi-Fi, or cloud service is needed to use the camera.

## Features

- Live USB camera preview with Android USB-host permission prompts (AUSBC library)
- Full-screen immersive preview with auto-hiding controls; tap to show or hide them
- Pinch-to-zoom, 90° preview rotation, horizontal and vertical flips
- Portrait or landscape control layout
- Photo mode, video mode, and central shutter button
- JPEG photos and MP4 video; optional phone microphone audio
- USB device diagnostics, reported preview sizes, and manual reconnection
- Automatic reconnection handling after USB unplug/replug
- MediaStore gallery copies under `Pictures/MouseHole` and `Movies/MouseHole`
- In-app About dialog with version and studio credit

## Compatibility

- Android 8.0+ (minSdk 26) with USB-host support and a compatible UVC USB camera
- USB-C endoscope or an appropriate USB OTG adapter
- The currently configured camera request is **640 × 480 YUYV**. Do not assume that 720p, 2K, MJPEG, or every UVC device works.
- Developed around a Nimborial USB-C endoscope; compatibility with other devices is not guaranteed.

## Install from GitHub Actions

1. Open [Actions](https://github.com/ALS-Sanbox/mousehole/actions) and select the Android APK build workflow.
2. Choose a successful run (or use **Run workflow**, if enabled).
3. Download the APK artifact from the run's **Artifacts** section and extract the ZIP if necessary.
4. Transfer the APK to your Android phone and open it using the phone's file manager.
5. Allow installation from that source when Android asks. You may disable that permission afterward.
6. Launch **Mouse Hole**, allow camera access, then connect the USB endoscope and approve its USB permission request.
7. For recording with audio, grant microphone permission **before connecting** the camera.

**Updating:** Install newer builds over the existing app only when the application ID and signing certificate match and the new version code is higher. GitHub Actions debug signing may change between runners; persistent signing is required for reliable in-place upgrades. Back up any important captures before reinstalling.

## Using the app

- **Photo icon:** select photo mode; tap the central shutter to save a JPEG.
- **Video icon:** select video mode; tap the central shutter to start/stop MP4 recording.
- **Pinch:** zoom the live preview. **Tap preview:** reveal/hide the overlay.
- **Side controls:** rotate 90°, flip horizontally/vertically, switch UI orientation, open gallery, reconnect camera, or open camera tools.
- **Settings / camera tools → About Mouse Hole:** see version, studio attribution, and GitHub link.
- The overlay automatically hides after a few seconds; tap to reveal it again.

Photos are copied to **Pictures/MouseHole** and videos to **Movies/MouseHole** through Android MediaStore. App-private capture files may also exist. Storage behavior can vary if saving fails.

## Build from source

Requirements: JDK 17, Android SDK Platform 36, Android Gradle Plugin 8.10.1, Gradle 8.11.1, Kotlin 2.2.10, and internet access for build dependencies.

```bash
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Alternatively, push to GitHub and use the configured workflow. The repository's workflow and Gradle files are the source of truth for current build configuration.

## Troubleshooting

| Problem | What to try |
| --- | --- |
| No image / black preview | Confirm the camera supports UVC and YUYV; approve USB access; disconnect/reconnect; inspect USB devices and reported sizes in camera tools. |
| Camera disconnected | Replug the camera and use the reconnect icon if automatic reconnection fails. |
| Video has no sound | Grant microphone permission in Android settings **before connecting** the camera, then reconnect. Silent video is supported. |
| No HD preview | The app requests 640×480 YUYV; higher advertised resolutions may not stream reliably. |
| Can't install update | Check signing certificate and version code. A differently signed APK may require uninstalling the old build. |
| Missing gallery files | Check Pictures/MouseHole and Movies/MouseHole; confirm capture completed. |
| GitHub build fails | Read the first explicit Gradle error in Actions. AUSBC uses a moving JitPack snapshot, which can break compatibility. |

For additional preview diagnostics see [BLACK_PREVIEW_TEST.md](BLACK_PREVIEW_TEST.md).

## Privacy and permissions

- **USB access:** required to communicate with the endoscope.
- **Camera permission:** requested for camera operation.
- **Microphone:** optional, for video audio.
- No account, Wi-Fi, or internet connection is required at runtime; building dependencies requires internet access.
- Captures are stored on the device. This README does not claim a formal security audit.

## Credits and licensing

**Developed by Afro Lion Studios, LLC.**

**AI disclosure:** This application was created with the assistance of artificial intelligence (AI), with project direction and testing by the developer.

Repository: https://github.com/ALS-Sanbox/mousehole

USB camera integration uses [AUSBC / AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera). Review its Apache-2.0 license and notices, plus the licenses of other dependencies. Check for a root LICENSE file before assuming the Mouse Hole source itself has an open-source redistribution license.

## Project status

Active experimental development. Features and device compatibility may change. Contributions and issue reports are welcome through GitHub.
