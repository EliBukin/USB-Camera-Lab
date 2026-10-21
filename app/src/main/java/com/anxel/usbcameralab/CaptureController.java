package com.anxel.usbcameralab;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import com.herohan.uvcapp.CameraHelper;
import com.serenegiant.usb.Size;

// Capture on top of the camera session: the mode, the shutter, the shot or recording in flight and the last saved file. It holds
// no Activity, only a Listener, so it can later live in a foreground service. Main thread only; MediaStore work for photos runs
// on its own "capture-io" thread, a recording on its own thread. Valid while the camera is OPEN: MainActivity calls onCameraOpen,
// and onCameraGone first on every way out of OPEN.
final class CaptureController {
    enum Mode { PHOTO, VIDEO } // TIMELAPSE comes in a later phase
    static final String APP_STOPPED="app stopped"; // onCameraGone reason when the app leaves the screen
    interface Listener {
        void captureChanged();                          // mode(), canShoot(), isBusy() or recording() changed
        void shutterFired();                            // a photo started: sound and flash
        void recordingStarting();                       // record was tapped: start sound
        void recordingProgress(long ms,long bytes);     // about once a second while recording
        void soundSource(String source);                // where the recording's sound comes from: "camera mic", "off (…)"
        void recordingEnded(String note);               // the file is finished: stop sound; note says why it stopped or failed, or null
        void lastSaved(Uri uri,boolean video,Bitmap thumb); // a photo or video was saved (or the newest one at startup); thumb may be null
        void captureFailed(String message);
    }
    private final Context context;
    private final MediaFiles files;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper()), io;
    private final HandlerThread ioThread=new HandlerThread("capture-io");
    private CameraHelper helper; // non-null while OPEN
    private Size stream;         // photos come out at the stream size, videos scaled into 1920 × 1080: the camera mode picked in MainActivity
    private String cameraName;   // USB product name, to find the camera's microphone
    private Mode mode=Mode.PHOTO;
    private PhotoCapture shot;   // at most one in flight: a 4K shot briefly needs ~100 MB (RGBA readback, Bitmap, JPEG)
    private VideoRecorder video; // the recording, until its file is finished
    private boolean stopping;    // video was asked to stop
    private boolean released;

    CaptureController(Context context,Listener listener) {
        this.context=context.getApplicationContext(); files=new MediaFiles(this.context); this.listener=listener;
        ioThread.start(); io=new Handler(ioThread.getLooper());
        io.post(() -> {
            files.deleteLeftovers();
            Uri newest=files.newest(); if(newest==null) return;
            Bitmap thumb=files.thumbnail(newest);
            main.post(() -> { if(!released) listener.lastSaved(newest,MediaFiles.isVideo(newest),thumb); });
        });
    }
    Mode mode() { return mode; }
    // In VIDEO mode the shutter starts a recording and stops it again; it is off while the file is being finished.
    boolean canShoot() {
        if(released) return false;
        if(video!=null) return !stopping;
        return helper!=null && shot==null;
    }
    boolean isBusy() { return shot!=null || video!=null; }
    boolean recording() { return video!=null; }
    // What the shutter makes at the current camera mode, e.g. "3840 × 2160", or for video "1920 × 1080\nfrom 2160p".
    String sizeLabel() {
        if(helper==null || stream==null) return null;
        if(mode==Mode.PHOTO) return stream.width+" × "+stream.height;
        int[] v=VideoRecorder.fit(stream.width,stream.height);
        return v[0]+" × "+v[1]+(v[1]==stream.height?"":"\nfrom "+stream.height+"p");
    }
    void setMode(Mode m) {
        if(m==mode || isBusy() || released) return;
        mode=m; listener.captureChanged();
    }

    void onCameraOpen(CameraHelper owner,Size size,String name) { helper=owner; stream=size; cameraName=name; listener.captureChanged(); }
    void onModeChanged(Size size) { if(helper!=null) { stream=size; listener.captureChanged(); } }
    // Idempotent. A shot in flight is not cancelled: finishing the file doesn't need the camera, and it is published if the
    // library still delivers it, or deleted at its timeout. A recording stops and its file is finished on its own thread.
    void onCameraGone(String reason) {
        if(helper==null) return;
        android.util.Log.i("UsbCameraLab","capture: camera gone ("+reason+")"+(shot!=null?", shot still finishing":"")+(video!=null?", recording stops":""));
        helper=null; stream=null;
        stopVideo(APP_STOPPED.equals(reason)?"the app left the screen":"the camera stopped");
        listener.captureChanged();
    }
    void shutter() {
        if(!canShoot()) return;
        if(mode==Mode.PHOTO) {
            shot=new PhotoCapture(helper,files,main,io,this::shotDone);
            shot.start();
            listener.shutterFired();
        } else if(video==null) {
            boolean sound=context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
            video=new VideoRecorder(context,helper,files,stream,cameraName,sound,main,recorderEvents);
            listener.recordingStarting();
            video.start();
        } else stopVideo(null);
        listener.captureChanged();
    }
    private void stopVideo(String reason) {
        if(video==null || stopping) return;
        stopping=true; video.stop(reason);
    }
    private void shotDone(PhotoCapture s,Uri saved,Bitmap thumb,String error) {
        if(s!=shot) return;
        shot=null;
        if(released) { ioThread.quitSafely(); return; }
        if(saved!=null) listener.lastSaved(saved,false,thumb);
        else if(s.owner==helper) listener.captureFailed("Couldn't save the photo ("+error+")"); // a shot from an ended session fails quietly
        listener.captureChanged();
    }
    private final VideoRecorder.Events recorderEvents=new VideoRecorder.Events() {
        public void progress(VideoRecorder r,long ms,long bytes) { if(r==video && !released) listener.recordingProgress(ms,bytes); }
        public void sound(VideoRecorder r,String source) { if(r==video && !released) listener.soundSource(source); }
        public void finished(VideoRecorder r,Uri saved,Bitmap thumb,String why) {
            if(r!=video) return;
            video=null; stopping=false;
            if(released) return;
            if(saved!=null) listener.lastSaved(saved,true,thumb);
            listener.recordingEnded(saved==null ? "Video not saved: "+why : why==null ? null : "Recording stopped: "+why+". The video is saved.");
            listener.captureChanged();
        }
    };
    // The owner is going away. A shot in flight still finishes its file, and so does a recording; the io thread ends after the shot.
    void release() {
        stopVideo("the app closed");
        released=true; helper=null;
        if(shot==null) ioThread.quitSafely();
    }
}
