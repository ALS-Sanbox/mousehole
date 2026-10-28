# Mouse Hole — Black Preview Troubleshooting Build

This revision is for a USB device detected by VID/PID, with reported resolutions, but intermittent `OPENED` / `ERROR` and a black preview.

## Changes
- Default first-launch mode changed from 1280×720 MJPEG to 640×480 MJPEG (a common UVC mode; not guaranteed).
- Added **MODE** button to cycle through: 640×480 MJPEG → 640×480 YUYV → 1280×720 MJPEG. It restarts the Activity's camera connection; unplug and replug the camera if it does not reconnect.
- Displays selected mode alongside camera state and records state information to Android Logcat under `MouseHoleUVC`.
- No forced automatic retries; auto-looping could leave USB resources in an unstable state.

## How to test
1. Use **one** device at a time (S23 or A16); disconnect USB camera before updating the APK.
2. Build with GitHub Actions and install the APK. It uses the same app ID, but Android may refuse installation if it is signed with a different debug key; uninstall the earlier test APK if necessary (back up files first).
3. Disconnect, open Mouse Hole, attach endoscope, allow USB camera access.
4. On black preview tap **MODE**, then reconnect camera if needed. Try each mode with a disconnect/reconnect, and note the exact status text.
5. If all three are black, send the exact USB INFO values (VID/PID), the full SIZES list, and the exact ERROR message. If available, capture a Logcat filtered by `MouseHoleUVC`, `CameraUVC`, `libUVCCamera`, and `libuvc`.

## Limitations
- A camera's advertised resolution may not match the available UVC streaming modes.
- `OPENED` confirms an open event, not that preview frames are reaching the display.
- A video format listed in device capabilities may still fail on a particular Android phone.
- This is a diagnostic code change, not a verified camera fix, and has not been compiled here.
