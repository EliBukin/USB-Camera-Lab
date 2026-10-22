package com.anxel.usbcameralab;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import com.herohan.uvcapp.*;
import com.serenegiant.usb.*;
import com.serenegiant.utils.UVCUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

// The camera session, one per process: the CameraHelper and its events, the connection state machine, the stream mode and its
// trial, and the capture on top (CaptureController and its recorders). MainActivity attaches while it is on screen: it renders
// the session, adds its preview surface, and builds the controls panel on the session's camera. Leaving the screen closes the
// camera. Main thread only.
final class CameraSession implements CaptureController.Listener {
    // The camera connects by itself while the app is on screen (on attach, and when a camera is plugged in) and stops when
    // the app leaves the screen or the camera is unplugged. After a failure the user decides: Try again, or replug.
    // CLOSING: closing an opened camera also closes its USB device (UVCCamera.close), and that onDeviceClose arrives
    // a moment later. Retrying waits until then, so the late event can't land on the next attempt.
    enum State { IDLE, CONNECTING, OPEN, CLOSING }
    // What the session needs from the screen showing it.
    interface Ui {
        void stateChanged(State state);
        void status(String message);
        void showAction(String label,Runnable onTap); // the one button, shown only when the user has to act; null hides it
        void askCameraPermission();                    // answered through permissionAnswered
        void opened();                                 // OPEN with mode() set, or attached while OPEN: show it, build the controls
        void modeChanged();                            // mode() switched in place
        void modePicked(Size mode);                    // the session switched by itself: the picker shows the new mode
    }
    static final String NO_CAMERA="Plug in a USB camera";
    private static CameraSession instance;
    final CaptureController capture;
    // Auto controls a time-lapse turned off ("Lock exposure / WB / focus"), as UVC control flags, on the camera with this key,
    // until the user answers the offer to turn them back on. The camera keeps them off across reconnects, so the offer waits for one.
    final List<Integer> locked=new ArrayList<>();
    String lockedCamera;
    private final Application app;
    private final UsbManager usb;
    private final SharedPreferences savedModes; // the user's pick per camera, saved once it streamed
    private final Handler timers=new Handler(Looper.getMainLooper()); // cleared when the camera leaves OPEN
    private final Handler later=new Handler(Looper.getMainLooper());  // not cleared by setState
    private Ui ui;
    private CaptureController.Listener bar; // the screen's capture bar
    // One CameraHelper per session, from attach until the camera closes; events from a released helper are ignored.
    private CameraHelper helper;
    private State state=State.IDLE;
    // The USB device is already open in this helper: reconnect with openCamera(). Calling selectDevice() on an
    // open device makes UVCAndroid register its camera callbacks again, so every later event arrives twice.
    private boolean deviceOpen;
    private boolean waitForUser; // last attempt failed or was declined: no automatic retry until Try again, a replug or attach
    private boolean askingPermission; // camera permission dialog is up
    private String closingMessage, statusText=NO_CAMERA;
    private UsbDevice selected;
    private Surface preview; // the screen's, while it has one
    // Stream modes: one Size per format and frame size, its fpsList highest first; `mode` is the one streaming.
    private final List<Size> modes=new ArrayList<>();
    private Size mode;
    // A camera can offer a mode it can't stream (USB 2.0 bandwidth, quirks), so a mode is on trial until its first frame:
    // `probe` is a tiny extra render target, drawn only when a camera frame came in (not the library frame callback: native
    // crash reports, capture-design.md §1.4; not a SurfaceTexture: nothing would consume it, and a full queue could stall the
    // renderer). `trying` is the mode on trial, `pick` when the user chose it, `wayBack` when a failure may fall back once:
    // a saved mode or a pick, to what pickMode then gives (the default, or the last pick that streamed). `fellBack` is the mode
    // that failed while that one fallback runs; after it the user decides as before.
    private static final int FIRST_FRAME_MS=5000;
    private ImageReader probe;
    private long probeStart;
    private Size trying, fellBack;
    private boolean pick, wayBack;
    // The newest file, for the thumbnail of a screen that attaches later.
    private Uri last;
    private boolean lastVideo;
    private Bitmap lastThumb;

    static CameraSession get(Context context) {
        if(instance==null) instance=new CameraSession((Application)context.getApplicationContext());
        return instance;
    }
    private CameraSession(Application app) {
        this.app=app;
        UVCUtils.init(app);
        usb=(UsbManager)app.getSystemService(Context.USB_SERVICE);
        savedModes=app.getSharedPreferences("modes",Context.MODE_PRIVATE);
        capture=new CaptureController(app,this);
    }
    State state() { return state; }
    Size mode() { return mode; }
    List<Size> modes() { return modes; }
    UsbDevice selected() { return selected; }
    CameraHelper helper() { return helper; } // non-null while OPEN

    // MainActivity.onStart: a new helper, and the camera connects.
    void attach(Ui screen,CaptureController.Listener captureBar) {
        ui=screen; bar=captureBar;
        if(last!=null) bar.lastSaved(last,lastVideo,lastThumb);
        ui.stateChanged(state); ui.status(statusText); hideAction();
        if(helper==null) {
            helper=new CameraHelper(); helper.setStateCallback(new Events(helper));
            // Applies to every openCamera of this helper. No library AudioRecord: it would open one at each camera open once the
            // app holds RECORD_AUDIO (video with the camera's mic records through its own path).
            helper.setImageCaptureConfig(helper.getImageCaptureConfig().setJpegCompressionQuality(95));
            helper.setVideoCaptureConfig(helper.getVideoCaptureConfig().setAudioCaptureEnable(false));
        }
        waitForUser=false; fellBack=null;
        connect(); // a camera that is already plugged in; later ones arrive through onAttach
    }
    // MainActivity.onStop: the camera closes. A run stopping because of it still tells that screen's bar how it ended
    // (sound, toast), until the screen is destroyed.
    void detach(Ui screen) {
        if(ui!=screen) return; // a newer screen took over
        setPreview(null); ui=null;
        if(!capture.recording()) bar=null;
        close();
    }
    // MainActivity.onDestroy.
    void forget(CaptureController.Listener captureBar) { if(bar==captureBar) bar=null; }
    // release() closes the camera and the USB device. Don't call setStateCallback(null) first: that drops the
    // USB monitor, and release() could no longer close the device.
    private void close() {
        if(helper!=null) { capture.onCameraGone(CaptureController.APP_STOPPED); helper.release(); helper=null; }
        if(state!=State.IDLE) setState(State.IDLE);
        deviceOpen=false; selected=null;
    }
    // The screen's preview surface, or null when it has none. The renderer draws to it only while OPEN.
    void setPreview(Surface s) {
        if(s==preview) return;
        if(preview!=null && state==State.OPEN) helper.removeSurface(preview);
        preview=s;
        if(s!=null && state==State.OPEN) helper.addSurface(s,false);
    }

    private void say(String value) { statusText=value; if(ui!=null) ui.status(value); android.util.Log.i("UsbCameraLab",value); }
    private static boolean video(UsbDevice d) { for(int i=0;i<d.getInterfaceCount();i++) if(d.getInterface(i).getInterfaceClass()==UsbConstants.USB_CLASS_VIDEO) return true; return false; }
    private void setState(State next) {
        if(state==State.OPEN && next!=State.OPEN) { capture.onCameraGone("state "+next); dropProbe(); }
        state=next; android.util.Log.i("UsbCameraLab","state "+next);
        if(next!=State.OPEN) { timers.removeCallbacksAndMessages(null); modes.clear(); mode=null; }
        if(ui!=null) ui.stateChanged(next);
    }
    private UsbDevice findCamera() {
        List<UsbDevice> devices=helper==null?null:helper.getDeviceList();
        if(devices!=null) for(UsbDevice d:devices) if(video(d)) return d;
        return null;
    }
    private void connect() {
        if(state!=State.IDLE || helper==null || askingPermission || ui==null) return;
        UsbDevice d=deviceOpen && selected!=null ? selected : findCamera();
        if(d==null) { hideAction(); say(NO_CAMERA); return; }
        if(app.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { askingPermission=true; ui.askCameraPermission(); return; }
        waitForUser=false; hideAction();
        trying=savedMode(d); pick=false; wayBack=fellBack==null && trying!=null; // a saved mode is on trial from the open on
        if(deviceOpen && selected!=null) { setState(State.CONNECTING); say("Opening camera…"); helper.openCamera(openParam(selected)); return; }
        selected=d; setState(State.CONNECTING);
        say(usb.hasPermission(d)?"Connecting…":"Allow USB access to the camera…");
        helper.selectDevice(d);
    }
    void permissionAnswered(boolean granted) { askingPermission=false; if(granted) connect(); }
    // The user has to act: show why, and the one button that helps.
    void needUser(String message) { needUser(message,"Try again",this::retry); }
    void needUser(String message,String label,Runnable onTap) { waitForUser=true; say(message); if(ui!=null) ui.showAction(label,onTap); }
    void retry() { waitForUser=false; connect(); }
    private void hideAction() { if(ui!=null) ui.showAction(null,null); }
    private final class Events implements ICameraHelper.StateCallback {
        private final CameraHelper owner;
        Events(CameraHelper owner) { this.owner=owner; }
        private boolean stale() { return owner!=helper; }
        // Also called again when a device gains USB permission, e.g. after Android's attach dialog.
        public void onAttach(UsbDevice d) {
            if(stale() || !video(d) || state!=State.IDLE || waitForUser || askingPermission) return;
            if(usb.hasPermission(d)) { connect(); return; }
            // Newly plugged in: Android's attach dialog (manifest USB filter) asks for access; asking too would stack two dialogs.
            say("Camera plugged in. Allow access in Android's dialog.");
            if(ui!=null) ui.showAction("Try again",CameraSession.this::retry);
        }
        public void onDeviceOpen(UsbDevice d,boolean first) {
            if(stale()) return;
            deviceOpen=true; selected=d;
            if(state==State.CONNECTING) { say("Opening camera…"); owner.openCamera(openParam(d)); }
        }
        public void onCameraOpen(UsbDevice d) {
            if(stale()) return;
            if(state!=State.CONNECTING) { if(state==State.IDLE) owner.closeCamera(); return; } // CLOSING: our closeCamera() is already queued
            setState(State.OPEN); selected=d;
            loadModes(owner.getSupportedSizeList());
            Size opened=owner.getPreviewSize(), wanted=pickMode(d);
            mode=wanted==null?opened:wanted;
            if(wanted!=null && !same(wanted,opened)) owner.setPreviewSize(wanted); // queued, so it applies before the stream starts
            owner.startPreview();
            if(preview!=null && preview.isValid()) owner.addSurface(preview,false);
            if(ui!=null) ui.opened();
            capture.onCameraOpen(owner,mode,d.getProductName());
            if(mode!=null) watch(false);
            StringBuilder all=new StringBuilder();
            for(Size m:modes) all.append(all.length()==0?"":"; ").append(m.width).append('x').append(m.height).append(' ').append(format(m)).append(' ').append(m.fpsList);
            android.util.Log.i("UsbCameraLab","modes: "+all+" | opened at "+(opened==null?"?":modeName(opened))+", streaming "+(mode==null?"?":modeName(mode)));
            say(fellBack==null || mode==null?"Camera open • preview running":didntStream(fellBack,mode));
        }
        public void onCameraClose(UsbDevice d) {
            if(stale()) return;
            deviceOpen=false;
            if(state==State.OPEN) closing("The camera stopped.");
        }
        public void onDeviceClose(UsbDevice d) {
            if(stale()) return;
            deviceOpen=false;
            if(state==State.CLOSING) { setState(State.IDLE); lost(closingMessage); }
            else if(state!=State.IDLE) { setState(State.IDLE); lost("Lost the camera connection."); }
        }
        public void onDetach(UsbDevice d) {
            if(stale()) return;
            boolean ours=selected!=null && selected.equals(d);
            if(ours) { deviceOpen=false; selected=null; fellBack=null; if(state!=State.IDLE) setState(State.IDLE); }
            if(ours || (state==State.IDLE && video(d))) { waitForUser=false; hideAction(); say("Camera unplugged. Plug it back in to continue."); }
        }
        public void onCancel(UsbDevice d) { if(!stale() && state==State.CONNECTING) { setState(State.IDLE); needUser("USB access to the camera was not allowed."); } }
        public void onError(UsbDevice d,CameraException e) {
            android.util.Log.e("UsbCameraLab","Camera error",e);
            if(!stale() && state!=State.IDLE) { deviceOpen=false; setState(State.IDLE); lost("Couldn't open the camera. Try again, or unplug it and plug it back in."); }
        }
    }
    private void closing(String message) {
        setState(State.CLOSING); closingMessage=message; say("Closing…");
        timers.postDelayed(() -> { if(state==State.CLOSING) { setState(State.IDLE); lost(closingMessage); } },3000); // fallback if onDeviceClose never comes
    }

    // ---- Stream mode: format, frame size and frame rate ----
    static String format(Size s) { return s.type==UVCCamera.UVC_VS_FRAME_MJPEG?"MJPEG":"YUYV"; }
    static String modeName(Size s) { return s.width+" × "+s.height+" "+format(s)+" "+s.fps+" fps"; }
    static boolean same(Size a,Size b) { return a!=null && b!=null && a.type==b.type && a.width==b.width && a.height==b.height && a.fps==b.fps; }
    static Size at(Size res,int fps) { return new Size(res.type,res.width,res.height,fps,res.fpsList); }
    static String cameraKey(UsbDevice d) { return String.format(Locale.US,"%04x:%04x %s",d.getVendorId(),d.getProductId(),d.getProductName()); }
    Size find(int type,int width,int height) { for(Size m:modes) if(m.type==type && m.width==width && m.height==height) return m; return null; }
    private void loadModes(List<Size> sizes) {
        modes.clear(); if(sizes==null) return;
        for(Size s:sizes) {
            Size m=find(s.type,s.width,s.height); // a camera may list a frame size twice
            if(m==null) { m=new Size(s.type,s.width,s.height,0,new ArrayList<>()); modes.add(m); }
            if(s.fpsList!=null) for(int f:s.fpsList) if(!m.fpsList.contains(f)) m.fpsList.add(f);
            if(!m.fpsList.contains(s.fps)) m.fpsList.add(s.fps);
        }
        for(Size m:modes) { Collections.sort(m.fpsList,Collections.reverseOrder()); m.fps=m.fpsList.get(0); }
        Collections.sort(modes,(a,b) -> a.type!=b.type ? (a.type==UVCCamera.UVC_VS_FRAME_MJPEG?-1:1) : b.width*b.height-a.width*a.height);
    }
    // `preferred` when the size offers it, else its highest rate up to 30 fps, else its lowest.
    static int rate(Size res,int preferred) {
        if(res.fpsList.contains(preferred)) return preferred;
        for(int f:res.fpsList) if(f<=30) return f;
        return res.fpsList.get(res.fpsList.size()-1);
    }
    private Size savedMode(UsbDevice d) {
        String[] p=savedModes.getString(cameraKey(d),"").split(",");
        try { int fps=Integer.parseInt(p[3]); return new Size(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),fps,Collections.singletonList(fps)); }
        catch(RuntimeException e) { return null; } // nothing saved for this camera
    }
    // The user's pick for this camera while it still offers it. Otherwise the largest MJPEG size within 1920 × 1080 (1080p when
    // offered) at up to 30 fps: the phone decodes every frame, and on the A20 (low-end SoC, USB 2.0) 4K previews at about
    // 11 fps against 30 for 1080p. null keeps the library's choice.
    private Size pickMode(UsbDevice d) {
        Size s=savedMode(d), m=s==null?null:find(s.type,s.width,s.height);
        if(m!=null && m.fpsList.contains(s.fps)) return at(m,s.fps);
        for(Size o:modes) if(o.type==UVCCamera.UVC_VS_FRAME_MJPEG && o.width<=1920 && o.height<=1080) return at(o,rate(o,30)); // largest first
        return null;
    }
    // Open straight in the wanted mode. When the camera lacks it UVCCamera.open falls back to its own default (the largest
    // MJPEG size), and onCameraOpen switches before the stream starts.
    private UVCParam openParam(UsbDevice d) {
        Size s=savedMode(d);
        return new UVCParam(s!=null?s:new Size(UVCCamera.UVC_VS_FRAME_MJPEG,1920,1080,30,Collections.singletonList(30)),0);
    }
    // Switch in place: stop, renegotiate, start (queued in order on the helper's thread). The camera, USB device, surfaces and
    // controls stay open, so there is no close/reopen round trip. If the camera refuses, UVCAndroid closes it and
    // onDeviceClose reports the lost connection. `pick`: the user's choice, saved once it streams.
    void applyMode(Size m,boolean pick) {
        if(state!=State.OPEN || mode==null || same(m,mode) || capture.isBusy()) return;
        android.util.Log.i("UsbCameraLab","mode "+modeName(mode)+" -> "+modeName(m)+" at "+SystemClock.uptimeMillis());
        helper.stopPreview(); helper.setPreviewSize(m); helper.startPreview();
        mode=m; if(ui!=null) ui.modeChanged(); // some cameras reset controls with the stream: the screen re-reads them
        capture.onModeChanged(m);
        if(pick) fellBack=null; // the user's new attempt
        watch(pick);
    }
    // Put `mode` on trial: the probe is queued on the helper's thread after the stream start, so only frames of this mode reach it.
    private void watch(boolean pick) {
        dropProbe();
        trying=mode; this.pick=pick; wayBack=fellBack==null && (pick || same(mode,savedMode(selected)));
        ImageReader r=ImageReader.newInstance(16,16,PixelFormat.RGBA_8888,2); probe=r; probeStart=SystemClock.uptimeMillis();
        r.setOnImageAvailableListener(p -> { Image f=p.acquireLatestImage(); if(f!=null) f.close(); if(p==probe) streams(); },timers);
        helper.addSurface(r.getSurface(),false);
        timers.postDelayed(noFrames,FIRST_FRAME_MS);
    }
    // The renderer lets go of a removed target on its own thread a moment later: close the reader only after that.
    private void dropProbe() {
        timers.removeCallbacks(noFrames); if(probe==null) return;
        ImageReader r=probe; probe=null;
        if(helper!=null) helper.removeSurface(r.getSurface());
        later.postDelayed(r::close,2000);
    }
    private void streams() {
        if(state!=State.OPEN || trying==null) return;
        android.util.Log.i("UsbCameraLab","streams: "+modeName(trying)+", first frame after "+(SystemClock.uptimeMillis()-probeStart)+" ms"+(pick?", remembered":""));
        if(pick) savedModes.edit().putString(cameraKey(selected),trying.type+","+trying.width+","+trying.height+","+trying.fps).apply();
        trying=null; fellBack=null; dropProbe();
    }
    // No frame in time while the camera stays open: back in place to what pickMode now gives, or, while a capture holds the
    // mode, by reconnecting (lost() after the close). With no way back the camera closes and the user decides.
    private final Runnable noFrames=this::noFrames;
    private void noFrames() {
        if(state!=State.OPEN || trying==null) return;
        android.util.Log.w("UsbCameraLab","no frame in "+modeName(trying)+" after "+FIRST_FRAME_MS+" ms");
        Size failed=trying, to=failedTrial(failed)?pickMode(selected):null;
        if(to!=null && same(to,failed)) to=null;
        if(to!=null && !capture.isBusy()) { fellBack=failed; say(didntStream(failed,to)); applyMode(to,false); if(ui!=null) ui.modePicked(to); return; }
        if(to==null) trying=null;
        dropProbe(); helper.closeCamera(); closing("No picture from the camera. Try again, or unplug it and plug it back in.");
    }
    // The mode on trial failed: a saved one is forgotten. True when this attempt may still fall back.
    private boolean failedTrial(Size m) {
        if(selected!=null && same(savedMode(selected),m)) savedModes.edit().remove(cameraKey(selected)).apply();
        return wayBack && fellBack==null;
    }
    // Where a failed attempt would ask the user: if it failed in a mode on trial with a way back, reconnect once instead
    // (pickMode then gives the default or the last pick that streamed), after a moment, like a Try again.
    private void lost(String message) {
        Size failed=trying; trying=null;
        if(failed==null || !failedTrial(failed)) { fellBack=null; needUser(message); return; }
        fellBack=failed; say(modeName(failed)+" didn't stream on this camera. Reconnecting…");
        timers.postDelayed(() -> { if(fellBack==failed) connect(); },500);
    }
    private static String didntStream(Size failed,Size to) { return modeName(failed)+" didn't stream on this camera; back to "+shortName(to); }
    // "1080p", "1080p 5 fps", "1440 × 1080"
    static String shortName(Size s) {
        String size=s.width==s.height*16/9?s.height+"p":s.width+" × "+s.height;
        return size+(s.fps==30?"":" "+s.fps+" fps");
    }

    // ---- CaptureController.Listener: to the screen's capture bar ----
    public void captureChanged() { if(bar!=null) bar.captureChanged(); }
    public void shutterFired() { if(bar!=null) bar.shutterFired(); }
    public void recordingStarting() { if(bar!=null) bar.recordingStarting(); }
    public void recordingProgress(long ms,long bytes) { if(bar!=null) bar.recordingProgress(ms,bytes); }
    public void timelapseProgress(int n,long ms) { if(bar!=null) bar.timelapseProgress(n,ms); }
    public void captureWarning(String message) { if(bar!=null) bar.captureWarning(message); }
    public void soundSource(String source) { if(bar!=null) bar.soundSource(source); }
    public void recordingEnded(String note) {
        if(bar!=null) bar.recordingEnded(note);
        if(ui==null) bar=null; // the screen left while the run stopped: that was the last it needed
    }
    public void lastSaved(Uri uri,boolean video,Bitmap thumb) { last=uri; lastVideo=video; lastThumb=thumb; if(bar!=null) bar.lastSaved(uri,video,thumb); }
    public void captureFailed(String message) { if(bar!=null) bar.captureFailed(message); }
}
