# USB Camera Lab
Initial Android USB camera prototype. Connects automatically while on screen; approve Camera + USB access the first time.
Local preview, device information, and every UVC control the camera reports (image, white balance, exposure,
focus/zoom/pan/tilt, anti-flicker, ...) with auto toggles and Reset all. English UI.
Photos: the shutter over the preview (bottom in portrait, right edge in landscape, also in full screen) saves a JPEG
(quality 95) at the current camera mode, e.g. 3840 × 2160 or 1920 × 1080, to Pictures/USB Camera Lab as
USBCam_yyyyMMdd_HHmmss.jpg through MediaStore, with no storage permission. The thumbnail opens the last photo in Gallery.
No video recording, microphone use, internet permission, or background capture.
The camera stops when the app leaves the screen or is unplugged, and reconnects on return or plug-in.
On plug-in Android offers to open USB Camera Lab for the camera; "always" skips the access prompt next time.
A Try again button appears only after access is declined or the camera fails.
Rotation keeps the stream running (the activity handles configuration changes; landscape puts controls beside the preview).
Camera mode (format, frame size, frame rate) is picked at the top of the controls and remembered per camera; the default
is MJPEG 1920 × 1080 (or the largest MJPEG size up to 1080p) at up to 30 fps. Switching restarts only the stream.
Tap the preview for full screen (system bars hidden); tap again or Back to return.
Uses Apache-2.0 UVCAndroid 1.0.13: https://github.com/shiyinghan/UVCAndroid
Android 10+ (minSdk 29), development target SDK 34; this prototype is not a Play Store release.
