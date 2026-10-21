# USB Camera Lab
Initial Android USB camera prototype. Connects automatically while on screen; approve Camera + USB access the first time.
Local preview, device information, and every UVC control the camera reports (image, white balance, exposure,
focus/zoom/pan/tilt, anti-flicker, ...) with auto toggles and Reset all. English UI.
Capture bar: PHOTO / VIDEO / TIME-LAPSE above the shutter in portrait, in a column on the right edge in landscape, also in
full screen. The chosen mode and the time-lapse interval are remembered across launches.
Photos: the shutter saves a JPEG (quality 95) at the current camera mode, e.g. 3840 × 2160 or 1920 × 1080, to
Pictures/USB Camera Lab as USBCam_yyyyMMdd_HHmmss.jpg through MediaStore, with no storage permission. The thumbnail
opens the last photo or video.
Video: switch the bar to VIDEO; the red button records H.264 MP4 to Movies/USB Camera Lab as USBCam_yyyyMMdd_HHmmss.mp4
(a pending MediaStore row until finished; deleted if no frame arrived). The stream is GPU-scaled into 1920 × 1080, the A20's
encoder limit (4K → 1920 × 1080, 2592 × 1944 → 1440 × 1080), at the stream's frame rate, ~0.2 bit per pixel per frame.
Sound: the microphone permission is asked at the first recording (denied: silent videos, and the recording says so).
The camera's own USB mic is used when Android lists it, else the phone's; a USB mic that sends only silence for 2 s is
swapped for the phone's mic (the XC-TECH camera's mic does that on the A20). AAC 48 kHz 128 kbps.
While recording: time and size over the preview, the sound source, and the camera mode and PHOTO / VIDEO are locked.
The file is finished and saved when you stop, the app leaves the screen, the camera stops or is unplugged, free storage
falls under 500 MB, or the phone reaches severe thermal status.
Time-lapse: one frame every 1, 2, 5, 10 or 30 s, 1 or 5 min (the interval chip next to the shutter; it shows what an hour
becomes, e.g. 5 s: 1 h → 24 s), played at 30 fps, H.264 High at the video size (≤ 1920 × 1080), ~16 Mbps, no sound, to
Movies/USB Camera Lab as USBCam_TL_yyyyMMdd_HHmmss_5s.mp4. Android's encoder input keeps one camera frame per interval
(KEY_MAX_FPS_TO_ENCODER) and retimes it (KEY_CAPTURE_RATE), so the app does no per-frame work. A new file starts every
60 minutes (_part2, _part3, …), each saved on its own, so a crash loses one hour at most. While running: frame count,
interval, video length and elapsed time over the preview; Dim turns this window to its lowest brightness under a black
cover with one dim status line (tap or Back wakes it; the phone's brightness setting is untouched). The interval chip's
menu also offers "Lock exposure / WB / focus": at the start the camera's auto exposure, white balance and focus are
switched off, so it holds what it had chosen, and when the run ends the app offers to turn them back on.
A time-lapse stops and saves like video, and also below 10 % battery (a warning at 20 %; not while charging).
Capture ends when the app leaves the screen (Home, screen off, another app): there is no background capture yet.
Hints: VIDEO with a stream above 1080p (4K decodes at ~11 fps on the A20) or under 24 fps offers a one-tap switch to
1080p; TIME-LAPSE offers the largest size within 1080p at the lowest frame rate the camera has (here 1920 × 1080 YUYV
5 fps: about a quarter less battery current than MJPEG 30 fps). Nothing switches by itself.
No internet permission.
The camera stops when the app leaves the screen or is unplugged, and reconnects on return or plug-in.
On plug-in Android offers to open USB Camera Lab for the camera; "always" skips the access prompt next time.
A Try again button appears only after access is declined or the camera fails.
Rotation keeps the stream running (the activity handles configuration changes; landscape puts controls beside the preview).
Camera mode (format, frame size, frame rate) is picked at the top of the controls and remembered per camera; the default
is MJPEG 1920 × 1080 (or the largest MJPEG size up to 1080p) at up to 30 fps. Switching restarts only the stream.
Tap the preview for full screen (system bars hidden); tap again or Back to return.
Uses Apache-2.0 UVCAndroid 1.0.13: https://github.com/shiyinghan/UVCAndroid
Android 10+ (minSdk 29), development target SDK 34; this prototype is not a Play Store release.
