package com.anxel.usbcameralab;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import com.herohan.uvcapp.CameraHelper;
import com.serenegiant.usb.Size;

// Capture on top of the camera session: the mode, the shutter, the shot or recording in flight and the last saved file. It holds
// no Activity, only a Listener (CameraSession, which passes it on to the screen's bar), and lives as long as the process. Main
// thread only; MediaStore work for photos runs on its own "capture-io" thread, a recording or time-lapse on its own thread. Valid
// while the camera is OPEN: CameraSession calls onCameraOpen, and onCameraGone first on every way out of OPEN. The mode and the
// time-lapse interval survive restarts.
final class CaptureController {
    enum Mode { PHOTO, VIDEO, TIMELAPSE }
    static final String APP_STOPPED="app stopped"; // onCameraGone reason when the app leaves the screen with no background service
    static final String PREFS="capture"; // also holds CaptureBar's settings
    interface Listener {
        void captureChanged();                          // mode(), canShoot(), isBusy() or recording() changed
        void shutterFired();                            // a photo started: sound and flash
        void recordingStarting();                       // record was tapped: start sound
        void recordingProgress(long ms,long bytes);     // about once a second while recording video
        void timelapseProgress(int frames,long ms);     // about once a second during a time-lapse: frames kept, wall time
        void captureWarning(String message);            // the run goes on, but the user should know (low battery)
        void soundSource(String source);                // where the recording's sound comes from: "camera mic", "off (…)"
        void recordingEnded(String note);               // video or time-lapse finished: stop sound; note says why it stopped or failed, or null
        void lastSaved(Uri uri,boolean video,Bitmap thumb); // a photo or video was saved (or the newest one at startup); thumb may be null
        void captureFailed(String message);
    }
    private final Context context;
    private final MediaFiles files;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper()), io;
    private final HandlerThread ioThread=new HandlerThread("capture-io");
    private CameraHelper helper; // non-null while OPEN
    private Size stream;         // photos come out at the stream size, videos scaled into 1920 × 1080: the camera mode picked in MainActivity
    private String cameraName;   // USB product name, to find the camera's microphone
    private Mode mode;
    private int interval;        // time-lapse seconds, one of TimelapseRecorder.INTERVALS
    private PhotoCapture shot;   // at most one in flight: a 4K shot briefly needs ~100 MB (RGBA readback, Bitmap, JPEG)
    private VideoRecorder video; // the recording, until its file is finished
    private TimelapseRecorder timelapse; // the time-lapse, until its last part is finished
    private boolean stopping;    // the recording or time-lapse was asked to stop

    CaptureController(Context context,Listener listener) {
        this.context=context.getApplicationContext(); files=new MediaFiles(this.context); this.listener=listener;
        prefs=this.context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        try { mode=Mode.valueOf(prefs.getString("mode",Mode.PHOTO.name())); } catch(IllegalArgumentException e) { mode=Mode.PHOTO; }
        interval=prefs.getInt("interval",5);
        boolean known=false; for(int i:TimelapseRecorder.INTERVALS) known|=i==interval;
        if(!known) interval=5;
        ioThread.start(); io=new Handler(ioThread.getLooper());
        io.post(() -> {
            files.deleteLeftovers();
            Uri newest=files.newest(); if(newest==null) return;
            Bitmap thumb=files.thumbnail(newest);
            main.post(() -> listener.lastSaved(newest,MediaFiles.isVideo(newest),thumb));
        });
    }
    Mode mode() { return mode; }
    int interval() { return interval; }
    // In VIDEO and TIMELAPSE mode the shutter starts a run and stops it again; it is off while the file is being finished.
    boolean canShoot() {
        if(video!=null || timelapse!=null) return !stopping;
        return helper!=null && shot==null;
    }
    boolean isBusy() { return shot!=null || video!=null || timelapse!=null; }
    boolean recording() { return video!=null || timelapse!=null; }
    boolean timelapseRunning() { return timelapse!=null; }
    // The stream: what photos come out at, and what the camera-mode hints compare against. null when the camera isn't open.
    Size stream() { return stream; }
    // What the shutter makes at the current camera mode, e.g. "3840 × 2160", or for video "1920 × 1080\nfrom 2160p".
    String sizeLabel() {
        if(helper==null || stream==null) return null;
        if(mode==Mode.PHOTO) return stream.width+" × "+stream.height;
        int[] v=VideoRecorder.fit(stream.width,stream.height); // time-lapse too
        return v[0]+" × "+v[1]+(v[1]==stream.height?"":"\nfrom "+stream.height+"p");
    }
    void setMode(Mode m) {
        if(m==mode || isBusy()) return;
        mode=m; prefs.edit().putString("mode",m.name()).apply(); listener.captureChanged();
    }
    void setInterval(int seconds) {
        if(seconds==interval || timelapse!=null) return;
        interval=seconds; prefs.edit().putInt("interval",seconds).apply(); listener.captureChanged();
    }

    void onCameraOpen(CameraHelper owner,Size size,String name) { helper=owner; stream=size; cameraName=name; listener.captureChanged(); }
    void onModeChanged(Size size) { if(helper!=null) { stream=size; listener.captureChanged(); } }
    // Idempotent. A shot in flight is not cancelled: finishing the file doesn't need the camera, and it is published if the
    // library still delivers it, or deleted at its timeout. A recording stops and its file is finished on its own thread.
    void onCameraGone(String reason) {
        if(helper==null) return;
        android.util.Log.i("UsbCameraLab","capture: camera gone ("+reason+")"+(shot!=null?", shot still finishing":"")+(video!=null?", recording stops":"")+(timelapse!=null?", time-lapse stops":""));
        helper=null; stream=null;
        stopRun(APP_STOPPED.equals(reason)?"the app left the screen":"the camera stopped");
        listener.captureChanged();
    }
    void shutter() {
        if(!canShoot()) return;
        if(mode==Mode.PHOTO) {
            shot=new PhotoCapture(helper,files,main,io,this::shotDone);
            shot.start();
            listener.shutterFired();
        } else if(recording()) stopRun(null);
        else if(mode==Mode.TIMELAPSE) {
            timelapse=new TimelapseRecorder(context,helper,files,stream,interval,main,timelapseEvents);
            listener.recordingStarting();
            timelapse.start();
        } else {
            boolean sound=context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
            video=new VideoRecorder(context,helper,files,stream,cameraName,sound,main,recorderEvents);
            listener.recordingStarting();
            video.start();
        }
        listener.captureChanged();
    }
    // Stops the run from outside the bar (the notification's Stop), as the shutter would.
    void stop() { if(!recording()) return; stopRun(null); listener.captureChanged(); }
    private void stopRun(String reason) {
        if(stopping) return;
        if(video!=null) video.stop(reason); else if(timelapse!=null) timelapse.stop(reason); else return;
        stopping=true;
    }
    private void shotDone(PhotoCapture s,Uri saved,Bitmap thumb,String error) {
        if(s!=shot) return;
        shot=null;
        if(saved!=null) listener.lastSaved(saved,false,thumb);
        else if(s.owner==helper) listener.captureFailed("Couldn't save the photo ("+error+")"); // a shot from an ended session fails quietly
        listener.captureChanged();
    }
    private final VideoRecorder.Events recorderEvents=new VideoRecorder.Events() {
        public void progress(VideoRecorder r,long ms,long bytes) { if(r==video) listener.recordingProgress(ms,bytes); }
        public void sound(VideoRecorder r,String source) { if(r==video) listener.soundSource(source); }
        public void finished(VideoRecorder r,Uri saved,Bitmap thumb,String why) {
            if(r!=video) return;
            video=null; stopping=false;
            if(saved!=null) listener.lastSaved(saved,true,thumb);
            listener.recordingEnded(saved==null ? "Video not saved: "+why : why==null ? null : "Recording stopped: "+why+". The video is saved.");
            listener.captureChanged();
        }
    };
    private final TimelapseRecorder.Events timelapseEvents=new TimelapseRecorder.Events() {
        public void progress(TimelapseRecorder r,int frames,long ms) { if(r==timelapse) listener.timelapseProgress(frames,ms); }
        public void warning(TimelapseRecorder r,String message) { if(r==timelapse) listener.captureWarning(message); }
        public void partSaved(TimelapseRecorder r,Uri saved,Bitmap thumb) { if(r==timelapse) listener.lastSaved(saved,true,thumb); }
        public void finished(TimelapseRecorder r,Uri saved,Bitmap thumb,int parts,String why) {
            if(r!=timelapse) return;
            timelapse=null; stopping=false;
            if(saved!=null) listener.lastSaved(saved,true,thumb);
            String what=parts>1?"The "+parts+" parts are saved.":"The video is saved.";
            listener.recordingEnded(saved==null ? "Time-lapse not saved: "+why : why==null ? null : "Time-lapse stopped: "+why+". "+what);
            listener.captureChanged();
        }
    };
}
