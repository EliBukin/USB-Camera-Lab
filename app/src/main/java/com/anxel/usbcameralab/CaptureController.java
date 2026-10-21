package com.anxel.usbcameralab;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import com.herohan.uvcapp.CameraHelper;
import com.serenegiant.usb.Size;

// Capture on top of the camera session: the shutter, the shot in flight and the last saved file. It holds no Activity, only a
// Listener, so it can later live in a foreground service. Main thread only; MediaStore work runs on its own "capture-io" thread.
// Valid while the camera is OPEN: MainActivity calls onCameraOpen, and onCameraGone first on every way out of OPEN.
final class CaptureController {
    enum Mode { PHOTO } // VIDEO and TIMELAPSE come in later phases
    interface Listener {
        void captureChanged();                 // canShoot() or isBusy() changed
        void shutterFired();                   // a shot started: sound and flash
        void lastPhoto(Uri uri,Bitmap thumb);  // a photo was saved (or the newest one at startup); thumb may be null
        void photoFailed(String message);
    }
    private final MediaFiles files;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper()), io;
    private final HandlerThread ioThread=new HandlerThread("capture-io");
    private CameraHelper helper; // non-null while OPEN
    private Size stream;         // photos come out at the stream size: the camera mode picked in MainActivity
    private PhotoCapture shot;   // at most one in flight: a 4K shot briefly needs ~100 MB (RGBA readback, Bitmap, JPEG)
    private boolean released;

    CaptureController(Context context,Listener listener) {
        files=new MediaFiles(context.getApplicationContext()); this.listener=listener;
        ioThread.start(); io=new Handler(ioThread.getLooper());
        io.post(() -> {
            files.deleteLeftovers();
            Uri newest=files.newestPhoto(); if(newest==null) return;
            Bitmap thumb=files.thumbnail(newest);
            main.post(() -> { if(!released) listener.lastPhoto(newest,thumb); });
        });
    }
    Mode mode() { return Mode.PHOTO; }
    boolean canShoot() { return helper!=null && shot==null && !released; }
    boolean isBusy() { return shot!=null; }
    String photoSize() { return helper==null || stream==null ? null : stream.width+" × "+stream.height; }

    void onCameraOpen(CameraHelper owner,Size size) { helper=owner; stream=size; listener.captureChanged(); }
    void onModeChanged(Size size) { if(helper!=null) { stream=size; listener.captureChanged(); } }
    // Idempotent. A shot in flight is not cancelled: finishing the file doesn't need the camera, and it is published if the
    // library still delivers it, or deleted at its timeout.
    void onCameraGone(String reason) {
        if(helper==null) return;
        android.util.Log.i("UsbCameraLab","capture: camera gone ("+reason+")"+(shot!=null?", shot still finishing":""));
        helper=null; stream=null; listener.captureChanged();
    }
    void shutter() {
        if(!canShoot()) return;
        shot=new PhotoCapture(helper,files,main,io,this::shotDone);
        shot.start();
        listener.shutterFired(); listener.captureChanged();
    }
    private void shotDone(PhotoCapture s,Uri saved,Bitmap thumb,String error) {
        if(s!=shot) return;
        shot=null;
        if(released) { ioThread.quitSafely(); return; }
        if(saved!=null) listener.lastPhoto(saved,thumb);
        else if(s.owner==helper) listener.photoFailed("Couldn't save the photo ("+error+")"); // a shot from an ended session fails quietly
        listener.captureChanged();
    }
    // The owner is going away. A shot in flight still finishes its file; the io thread ends after it.
    void release() {
        released=true; helper=null;
        if(shot==null) ioThread.quitSafely();
    }
}
