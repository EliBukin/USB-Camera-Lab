# USB Camera Lab
Initial Android USB camera prototype. Connects automatically while on screen; approve Camera + USB access the first time.
Material 3 (Material Components for Android, dark theme), English UI. Four tabs in a bottom bar (portrait) or a rail
(landscape): Home, Photo, Video, Time-lapse. One live picture stays on top of every tab and never restarts.
Home: the camera card (status, and Try again or Open app settings only when needed), the camera mode, and every UVC
control the camera reports (image, white balance, exposure, focus/zoom/pan/tilt, anti-flicker, ...) as section cards with
units, auto switches that grey their slider, and Reset all.
Photo, Video and Time-lapse choose the capture mode; each has its panel under the picture and a row of thumbnail, shutter
and Controls (a sheet with the same controls that leaves the picture visible). In landscape the capture tabs show the
picture at full height with the shutter column at the edge (system bars hidden). The tab and the time-lapse interval are
remembered across launches.
Photos: the shutter saves a JPEG (quality 95) at the current camera mode, e.g. 3840 × 2160 or 1920 × 1080, to
Pictures/USB Camera Lab as USBCam_yyyyMMdd_HHmmss.jpg through MediaStore, with no storage permission. The thumbnail
opens the last photo or video.
Video: the Video tab's red button records H.264 MP4 to Movies/USB Camera Lab as USBCam_yyyyMMdd_HHmmss.mp4
(a pending MediaStore row until finished; deleted if no frame arrived). The stream is GPU-scaled into 1920 × 1080, the A20's
encoder limit (4K → 1920 × 1080, 2592 × 1944 → 1440 × 1080), at the stream's frame rate, ~0.2 bit per pixel per frame.
Sound: the microphone permission is asked at the first recording (denied: silent videos, and the recording says so).
The camera's own USB mic is used when Android lists it, else the phone's; a USB mic that sends only silence for 2 s is
swapped for the phone's mic (the XC-TECH camera's mic does that on the A20). AAC 48 kHz 128 kbps.
While recording: time and size over the preview, the sound source, a red dot on the running tab, and the camera mode is locked;
the other capture tabs show their shutter off with "Go to …".
The file is finished and saved when you stop, the app leaves the screen, the camera stops or is unplugged, free storage
falls under 500 MB, or the phone reaches severe thermal status.
Time-lapse: one frame every 1, 2, 5, 10 or 30 s, 1 or 5 min (chips on the Time-lapse tab; it shows what an hour
becomes, e.g. 5 s: 1 h → 24 s), played at 30 fps, H.264 High at the video size (≤ 1920 × 1080), ~16 Mbps, no sound, to
Movies/USB Camera Lab as USBCam_TL_yyyyMMdd_HHmmss_5s.mp4. Android's encoder input keeps one camera frame per interval
(KEY_MAX_FPS_TO_ENCODER) and retimes it (KEY_CAPTURE_RATE), so the app does no per-frame work. A new file starts every
60 minutes (_part2, _part3, …), each saved on its own, so a crash loses one hour at most. While running: frames, video so far, elapsed
time, the part and the hour's progress; Dim turns this window to its lowest brightness under a black
cover with one dim status line (tap or Back wakes it; the phone's brightness setting is untouched). The tab's
"Lock exposure, white balance and focus" switch: at the start the camera's auto exposure, white balance and focus are
switched off, so it holds what it had chosen, and when the run ends the app offers to turn them back on.
A time-lapse stops and saves like video, and also below 10 % battery (a warning at 20 %; not while charging).
Background: a video or time-lapse goes on when the app leaves the screen (Home, screen off, lock, another app in front).
The record tap starts a foreground service (type camera, plus microphone for a video with sound) that keeps the process
running, holds a partial wake lock, and shows an ongoing notification: "Time-lapse · frame 154 · every 5 s · 12:50" or
"Recording · 01:23 · 145 MB" with the sound source. Its Stop finishes and saves the file and removes the notification; a
tap opens the app, where the preview, progress and controls come back with no reconnect. A run that stops by itself while
the app is away (unplug, battery, storage, heat, an error) saves what it has and leaves a notification saying why. When a
run ends while the app is away, the camera closes and the service stops. Android 13+ asks for notifications at the first
video or time-lapse. Samsung: set the app's Battery to "Unrestricted" for long screen-off runs.
Hints (cards on the Video and Time-lapse tabs, and in the mode sheet from the chip on the picture): Video with a stream above 1080p (4K decodes at ~11 fps on the A20) or under 24 fps offers a one-tap switch to
1080p; Time-lapse offers the largest size within 1080p at the lowest frame rate the camera has (here 1920 × 1080 YUYV
5 fps: about a quarter less battery current than MJPEG 30 fps). Nothing switches by itself.
Camera info (Home's camera card, ⋮ > Camera info): a read-only page over the app (the preview keeps running behind it)
that shows what the plugged-in camera is and offers, decoded from its USB descriptors: name, manufacturer, serial,
VID:PID and whether the vendor ID is in the public USB ID list, firmware version (bcdDevice), USB version and speed,
power; the UVC version and its terminals and units; every control the camera declares and whether this app shows it;
every format with its frame sizes and frame rates, still-image support and the streaming endpoint; USB audio and
Android's name for that input; and the extension units (vendor controls), read with GET requests only (GET_INFO,
GET_LEN, GET_CUR, GET_MIN, GET_MAX, GET_DEF; never a SET). Copy as text puts the whole report on the clipboard.
No internet permission.
With nothing recording, the camera stops when the app leaves the screen or is unplugged, and reconnects on return or plug-in.
On plug-in Android offers to open USB Camera Lab for the camera; "always" skips the access prompt next time.
Try again (or Open app settings) appears only after access is declined or the camera fails.
Rotation keeps the stream running (the activity handles configuration changes).
Camera mode (format, frame size, frame rate) is picked on Home (or from the chip on the picture) and remembered per camera; the default
is MJPEG 1920 × 1080 (or the largest MJPEG size up to 1080p) at up to 30 fps. Switching restarts only the stream.
Tap the picture for full screen (system bars hidden, the capture tab's shutter stays); tap again or Back to return.
Messages are snackbars ("Video saved · View").
Uses Apache-2.0 UVCAndroid 1.0.13: https://github.com/shiyinghan/UVCAndroid
Android 10+ (minSdk 29), development target SDK 34; this prototype is not a Play Store release.
