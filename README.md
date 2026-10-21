# USB Camera Lab
Initial Android USB camera prototype. Connect explicitly and approve Camera + USB access.
Local preview, device information, and every UVC control the camera reports (image, white balance, exposure,
focus/zoom/pan/tilt, anti-flicker, ...) with auto toggles and Reset all. English UI.
No recording, microphone use, internet permission, or background capture.
Camera closes when the activity leaves the foreground; tap Connect to resume.
Rotation keeps the stream running (the activity handles configuration changes; landscape puts controls beside the preview).
After unplug/replug or a disconnect, tap Connect again; Android asks for USB access again after a replug.
Uses Apache-2.0 UVCAndroid 1.0.13: https://github.com/shiyinghan/UVCAndroid
Development target SDK 34; this prototype is not a Play Store release.
