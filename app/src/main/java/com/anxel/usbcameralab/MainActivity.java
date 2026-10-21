package com.anxel.usbcameralab;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.herohan.uvcapp.*;
import com.serenegiant.usb.*;
import com.serenegiant.utils.UVCUtils;
import java.util.List;

public class MainActivity extends Activity {
    // CLOSING: closing an opened camera also closes its USB device (UVCCamera.close), and that onDeviceClose arrives
    // a moment later. Connect stays disabled until then, so the late event can't land on the next attempt.
    private enum State { IDLE, CONNECTING, OPEN, CLOSING }
    private static final String IDLE_DETAILS = "תצוגה מקומית • ללא הקלטה";
    private static final String DISCONNECTED = "מנותק — לחץ על התחברות כדי להתחיל";
    // One CameraHelper per foreground session (onStart..onStop); events from a released helper are ignored.
    private CameraHelper helper;
    private State state = State.IDLE;
    // The USB device is already open in this helper: reconnect with openCamera(). Calling selectDevice() on an
    // open device makes UVCAndroid register its camera callbacks again, so every later event arrives twice.
    private boolean deviceOpen;
    private boolean cameraRequested; // openCamera() was posted in the current attempt
    private String closingMessage;
    private UsbDevice selected;
    private UVCControl control;
    private final int[] pendingControl = {-1, -1}; // brightness, contrast (percent) waiting to be sent
    private boolean controlQueued;
    private LinearLayout root, header, panel, controls;
    private ScrollView side;
    private FrameLayout previewBox;
    private SurfaceView preview;
    private double aspect = 4.0/3.0;
    private TextView status, details;
    private Button connect, stop, settings;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        UVCUtils.init(getApplication());
        root=new LinearLayout(this); root.setPadding(24,24,24,16); root.setBackgroundColor(0xff101820);
        header=new LinearLayout(this); header.setOrientation(LinearLayout.VERTICAL);
        header.addView(text("USB Camera Lab",24));
        status=text("חבר מצלמת USB ולחץ על התחברות",16); header.addView(status);
        settings=new Button(this); settings.setText("פתח הגדרות אפליקציה"); settings.setVisibility(View.GONE); header.addView(settings,new LinearLayout.LayoutParams(-2,-2));
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",getPackageName(),null))));
        previewBox=new FrameLayout(this);
        preview=new SurfaceView(this); previewBox.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        previewBox.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob) -> fitPreview());
        panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        details=text(IDLE_DETAILS,14); panel.addView(details);
        LinearLayout buttons=new LinearLayout(this); panel.addView(buttons);
        connect=new Button(this); connect.setText("התחברות"); buttons.addView(connect); connect.setOnClickListener(v -> connect());
        stop=new Button(this); stop.setText("ניתוק"); buttons.addView(stop); stop.setOnClickListener(v -> disconnect());
        controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); panel.addView(controls);
        side=new ScrollView(this); side.addView(panel,new FrameLayout.LayoutParams(-1,-2));
        root.addView(previewBox); root.addView(side);
        arrange(getResources().getConfiguration());
        setContentView(root);
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { if(state==State.OPEN) helper.addSurface(h.getSurface(),false); }
            public void surfaceChanged(SurfaceHolder h,int f,int w,int height) {}
            public void surfaceDestroyed(SurfaceHolder h) { if(state==State.OPEN) helper.removeSurface(h.getSurface()); }
        });
        setState(State.IDLE);
    }
    // Rotation arrives here (manifest configChanges), so the stream keeps running; landscape puts the controls beside the preview.
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); arrange(c); }
    private void arrange(Configuration c) {
        ViewGroup parent=(ViewGroup)header.getParent(); if(parent!=null) parent.removeView(header);
        if(c.orientation==Configuration.ORIENTATION_LANDSCAPE) {
            panel.addView(header,0,new LinearLayout.LayoutParams(-1,-2)); root.setOrientation(LinearLayout.HORIZONTAL);
            previewBox.setLayoutParams(new LinearLayout.LayoutParams(0,-1,1));
            side.setLayoutParams(new LinearLayout.LayoutParams(dp(260),-1)); side.setPaddingRelative(dp(12),0,0,0);
        } else {
            root.addView(header,0,new LinearLayout.LayoutParams(-1,-2)); root.setOrientation(LinearLayout.VERTICAL);
            previewBox.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1));
            side.setLayoutParams(new LinearLayout.LayoutParams(-1,-2)); side.setPaddingRelative(0,0,0,0);
        }
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView text(String value,int size) { TextView t=new TextView(this); t.setText(value); t.setTextColor(0xffe8eff5); t.setTextSize(size); t.setPadding(0,8,0,8); return t; }
    private void say(String value) { status.setText(value); android.util.Log.i("UsbCameraLab",value); }
    private boolean video(UsbDevice d) { for(int i=0;i<d.getInterfaceCount();i++) if(d.getInterface(i).getInterfaceClass()==UsbConstants.USB_CLASS_VIDEO) return true; return false; }
    private void setState(State next) {
        state=next; android.util.Log.i("UsbCameraLab","state "+next);
        connect.setEnabled(next==State.IDLE); stop.setEnabled(next==State.CONNECTING || next==State.OPEN);
        if(next==State.OPEN) { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); return; }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(next!=State.CONNECTING) cameraRequested=false;
        ui.removeCallbacksAndMessages(null); controlQueued=false; pendingControl[0]=pendingControl[1]=-1;
        control=null; controls.removeAllViews(); details.setText(IDLE_DETAILS);
    }
    private void connect() {
        if(state!=State.IDLE || helper==null) return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CAMERA},1); return; }
        settings.setVisibility(View.GONE);
        if(deviceOpen && selected!=null) { setState(State.CONNECTING); say("פותח את המצלמה…"); cameraRequested=true; helper.openCamera(); return; }
        List<UsbDevice> devices=helper.getDeviceList();
        if(devices!=null) for(UsbDevice d:devices) if(video(d)) { selected=d; setState(State.CONNECTING); say("מבקש גישה למצלמה…"); helper.selectDevice(d); return; }
        say(devices==null || devices.isEmpty() ? "לא נמצאה מצלמה. בדוק חיבור ונסה שוב." : "לא נמצא התקן וידאו USB");
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=1) return;
        if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) { connect(); return; }
        // Denied with no rationale: Android won't show the dialog again (or it was dismissed), so offer the app's settings page.
        boolean blocked=results.length>0 && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
        settings.setVisibility(blocked?View.VISIBLE:View.GONE);
        say(blocked?"נדרשת הרשאת מצלמה. אם חלון הבקשה לא מופיע, אפשר אותה בהגדרות האפליקציה.":"נדרשת הרשאת מצלמה כדי להציג וידאו");
    }
    private final class Events implements ICameraHelper.StateCallback {
        private final CameraHelper owner;
        Events(CameraHelper owner) { this.owner=owner; }
        private boolean stale() { return owner!=helper; }
        public void onAttach(UsbDevice d) { if(!stale() && state==State.IDLE && video(d) && settings.getVisibility()!=View.VISIBLE) say("מצלמה זמינה — לחץ על התחברות"); }
        public void onDeviceOpen(UsbDevice d,boolean first) {
            if(stale()) return;
            deviceOpen=true; selected=d;
            if(state==State.CONNECTING) { say("פותח את המצלמה…"); cameraRequested=true; owner.openCamera(); }
        }
        public void onCameraOpen(UsbDevice d) {
            if(stale()) return;
            if(state!=State.CONNECTING) { if(state==State.IDLE) owner.closeCamera(); return; } // CLOSING: our closeCamera() is already queued
            setState(State.OPEN); selected=d;
            owner.startPreview();
            if(preview.getHolder().getSurface().isValid()) owner.addSurface(preview.getHolder().getSurface(),false);
            Size s=owner.getPreviewSize();
            if(s!=null) { aspect=(double)s.width/s.height; preview.getHolder().setFixedSize(s.width,s.height); fitPreview(); }
            String name=d.getProductName();
            details.setText((name==null?"מצלמת USB":name)+(s==null?"":" • "+s.width+" × "+s.height));
            say("המצלמה פתוחה • תצוגה פעילה");
            ui.postDelayed(MainActivity.this::showControls,700);
        }
        public void onCameraClose(UsbDevice d) {
            if(stale()) return;
            deviceOpen=false;
            if(state==State.OPEN) closing("המצלמה נסגרה — לחץ על התחברות");
        }
        public void onDeviceClose(UsbDevice d) {
            if(stale()) return;
            deviceOpen=false;
            if(state==State.CLOSING) { setState(State.IDLE); say(closingMessage); }
            else if(state!=State.IDLE) { setState(State.IDLE); say("החיבור למצלמה נסגר"); }
        }
        public void onDetach(UsbDevice d) {
            if(stale()) return;
            boolean ours=selected!=null && selected.equals(d);
            if(ours) { deviceOpen=false; selected=null; if(state!=State.IDLE) setState(State.IDLE); }
            if(ours || (state==State.IDLE && video(d))) say("המצלמה נותקה מה־USB. חבר אותה שוב ולחץ על התחברות");
        }
        public void onCancel(UsbDevice d) { if(!stale() && state==State.CONNECTING) { setState(State.IDLE); say("הגישה למצלמה לא אושרה. אפשר לנסות שוב."); } }
        public void onError(UsbDevice d,CameraException e) {
            android.util.Log.e("UsbCameraLab","Camera error",e);
            if(!stale() && state!=State.IDLE) { deviceOpen=false; setState(State.IDLE); say("לא ניתן לפתוח את המצלמה. נתק וחבר שוב."); }
        }
    }
    private void fitPreview() {
        int w=previewBox.getWidth(),h=previewBox.getHeight(); if(w==0 || h==0)return;
        if(w/(double)h>aspect) w=(int)(h*aspect); else h=(int)(w/aspect);
        android.view.ViewGroup.LayoutParams old=preview.getLayoutParams();
        if(old.width!=w || old.height!=h) preview.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));
    }
    private void showControls() {
        controls.removeAllViews(); if(state!=State.OPEN) return;
        control=helper.getUVCControl(); if(control==null) return;
        if(control.checkSupportFlag(UVCControl.PU_BRIGHTNESS_CONTROL)) slider("בהירות",control.getBrightnessPercent(),0);
        if(control.checkSupportFlag(UVCControl.PU_CONTRAST_CONTROL)) slider("ניגודיות",control.getContrastPercent(),1);
        if(controls.getChildCount()==0) controls.addView(text("המצלמה אינה מדווחת על שליטה בבהירות או בניגודיות",14));
    }
    private void slider(String label,int initial,int which) {
        controls.addView(text(label,14)); SeekBar bar=new SeekBar(this); bar.setMax(100); bar.setProgress(initial); controls.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {}
            public void onProgressChanged(SeekBar b,int value,boolean user) { if(user) queueControl(which,value); }
            public void onStopTrackingTouch(SeekBar b) { queueControl(which,b.getProgress()); }
        });
    }
    // Live sliders: coalesce drag updates and send at most one UVC control write per slider every 50 ms.
    private void queueControl(int which,int value) {
        pendingControl[which]=value;
        if(!controlQueued) { controlQueued=true; ui.postDelayed(applyControls,50); }
    }
    private final Runnable applyControls=() -> {
        controlQueued=false;
        if(state!=State.OPEN || control==null) return;
        if(pendingControl[0]>=0) control.setBrightnessPercent(pendingControl[0]);
        if(pendingControl[1]>=0) control.setContrastPercent(pendingControl[1]);
        pendingControl[0]=pendingControl[1]=-1;
    };
    private void disconnect() {
        if((state!=State.CONNECTING && state!=State.OPEN) || helper==null) return;
        boolean deviceCloses=state==State.OPEN || cameraRequested;
        helper.stopPreview(); helper.closeCamera();
        if(deviceCloses) closing(DISCONNECTED); else { setState(State.IDLE); say(DISCONNECTED); }
    }
    private void closing(String message) {
        setState(State.CLOSING); closingMessage=message; say("מתנתק…");
        ui.postDelayed(() -> { if(state==State.CLOSING) { setState(State.IDLE); say(closingMessage); } },3000); // fallback if onDeviceClose never comes
    }
    @Override protected void onStart() {
        super.onStart();
        helper=new CameraHelper(); helper.setStateCallback(new Events(helper));
        if(settings.getVisibility()==View.VISIBLE && checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) { settings.setVisibility(View.GONE); say("הרשאת המצלמה ניתנה — לחץ על התחברות"); }
    }
    @Override protected void onStop() {
        // release() closes the camera and the USB device. Don't call setStateCallback(null) first: that drops the
        // USB monitor, and release() could no longer close the device.
        if(helper!=null) { helper.release(); helper=null; }
        if(state!=State.IDLE) { setState(State.IDLE); say(DISCONNECTED); }
        deviceOpen=false; selected=null;
        super.onStop();
    }
}
