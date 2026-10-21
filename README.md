# USB Camera Lab
Initial Android USB camera prototype. Connects automatically while on screen; approve Camera + USB access the first time.
Local preview, device information, and every UVC control the camera reports (image, white balance, exposure,
focus/zoom/pan/tilt, anti-flicker, ...) with auto toggles and Reset all. English UI.
No recording, microphone use, internet permission, or background capture.
The camera stops when the app leaves the screen or is unplugged, and reconnects on return or plug-in.
On plug-in Android offers to open USB Camera Lab for the camera; "always" skips the access prompt next time.
A Try again button appears only after access is declined or the camera fails.
Rotation keeps the stream running (the activity handles configuration changes; landscape puts controls beside the preview).
Uses Apache-2.0 UVCAndroid 1.0.13: https://github.com/shiyinghan/UVCAndroid
Development target SDK 34; this prototype is not a Play Store release.
