# Mouse Hole — USB-C Endoscope for Samsung Galaxy S23

![Mouse Hole app icon — mouse and illuminated inspection camera](mouse-hole-logo.png)

**Branding:** The launcher icon uses the text-free Mouse Hole logo provided by the project owner. Android displays **Mouse Hole** as the application label beneath the icon.

**Experimental Android UVC camera app.** Designed for the Samsung Galaxy S23 and a Nimborial USB-C endoscope, assuming the endoscope uses the standard USB Video Class (UVC) interface. The specific camera has **not** been verified.

## Features in this starter

- USB-host device authorization via the AUSBC library
- Live external camera preview; USB diagnostics and reported preview sizes
- JPEG photos and MP4 video recording
- Optional phone microphone audio (grant the microphone permission before connecting the camera)
- Photos in `Pictures/MouseHole` and video in `Movies/MouseHole` using MediaStore
- No Wi-Fi or account required

The preview is initially requested at **1280 × 720**. 2K is **not** guaranteed; supported resolutions depend on the attached endoscope and may require code changes. The app does not implement dual-lens switching, LED brightness adjustment or automated resolution selection yet. This is an **untested prototype**, not a production release.

## Build an APK with GitHub Actions (no Android Studio required)

1. Open your GitHub repository: https://github.com/ALS-Sanbox/mousehole
2. Download and extract the `MouseHole_Android_GitHub.zip` file. Its contents include `app`, `.github`, `build.gradle.kts`, and `settings.gradle.kts`.
3. Upload **the contents**, not the enclosing directory, to the repository using **Add file → Upload files**. GitHub's web uploader may not preserve empty folders; this is fine. Make sure `.github/workflows/android-apk.yml` exists in the uploaded repository. If the web uploader ignores dot-prefixed paths, use GitHub's **Create new file** with the exact name `.github/workflows/android-apk.yml` and paste the YAML from the ZIP.
4. Commit the changes to the `main` branch. The workflow should run automatically on push. Or visit **Actions → Build Mouse Hole APK → Run workflow**.
5. Open **Actions**, select the newest workflow run and inspect the **Build debug APK** step. A green check means the APK compiled.
6. At the bottom of a successful workflow run, under **Artifacts**, download **MouseHole-S23-debug-APK**. Unzip this GitHub artifact and copy `app-debug.apk` onto your Galaxy S23.
7. On the phone, open the APK in **My Files** and approve Android's "Allow from this source" installation prompt for the app you used to open it. Turn that setting back off afterward if desired.
8. Launch **Mouse Hole**, grant the camera permission and optionally microphone permission; plug in the endoscope and approve the USB access prompt.

**Note:** The workflow creates a *debug-signed* APK, appropriate for private testing. GitHub Actions runs the build on GitHub's hosted runner. It does not publish to the Play Store. The APK is downloadable from the successful workflow run, not directly from the repo's Code tab.

## Troubleshooting

- **Build failed — dependency unavailable:** The starter relies on `com.github.ernestp.AndroidUSBCamera:libausbc:master-SNAPSHOT` from JitPack. This is a moving third-party snapshot. Check the Actions log for resolution or source incompatibility; a fixed, tested release dependency or vendored library may be required. **A successful APK build is not guaranteed by this ZIP.**
- **App installs but camera stays black:** Check USB INFO for a VID/PID. Ensure the phone supports USB host (Galaxy S23 does); try replugging and granting USB permission. The probe must expose UVC; some cameras use proprietary USB protocols. Try a known-working UVC viewer to distinguish camera/USB compatibility from app issues.
- **No microphone audio:** Enable Microphone permission in Android Settings → Apps → Mouse Hole → Permissions, then fully close/reopen the app and reconnect the USB camera before recording.
- **No 2K:** Check SIZES; camera advertisements are not proof of available USB stream modes. This prototype defaults to 720p.
- **Photos/videos missing:** Look in Gallery, `Pictures/MouseHole`, or `Movies/MouseHole`. App-private originals may remain if the Gallery copy fails.
- **Action never ran:** Check repository Settings → Actions → General for workflow permissions and verify that the YAML file is on the `main` branch under `.github/workflows/`.

## Source and licensing

The app uses [AUSBC / AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera) from JitPack. Review its Apache-2.0 license, notices, and upstream dependencies before redistribution. Camera access is local; no network permission is requested by this app. Gradle/JitPack requires internet **at build time only**.

## Build locally (optional)

Install JDK 17, Android SDK Platform 35 and build tools, and Gradle 8.9. Run `gradle :app:assembleDebug`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
