package com.anxel.usbcameralab;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.herohan.uvcapp.*;
import com.serenegiant.usb.*;
import com.serenegiant.utils.UVCUtils;
import java.util.List;

public class MainActivity extends Activity {
    private CameraHelper helper;
    private SurfaceView preview;
    private FrameLayout previewBox;
    private double aspect = 4.0/3.0;
    private TextView status, details;
    private LinearLayout controls;
    private boolean opened, connecting, foreground;
    private UsbDevice selected;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        UVCUtils.init(getApplication());
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24,24,24,16);
        layout.setBackgroundColor(0xff101820);
        TextView title=text("USB Camera Lab",24); layout.addView(title);
        status=text("חבר מצלמת USB ולחץ על התחברות",16); layout.addView(status);
        previewBox=new FrameLayout(this); layout.addView(previewBox,new LinearLayout.LayoutParams(-1,0,1));
        preview=new SurfaceView(this); previewBox.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        previewBox.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob) -> fitPreview());
        details=text("תצוגה מקומית • ללא הקלטה",14); layout.addView(details);
        LinearLayout buttons=new LinearLayout(this); layout.addView(buttons);
        Button connect=new Button(this); connect.setText("התחברות"); buttons.addView(connect); connect.setOnClickListener(v -> connect());
        Button stop=new Button(this); stop.setText("ניתוק"); buttons.addView(stop); stop.setOnClickListener(v -> disconnect());
        controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); layout.addView(controls);
        setContentView(layout);
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { if(opened && helper!=null) helper.addSurface(h.getSurface(),false); }
            public void surfaceChanged(SurfaceHolder h,int f,int w,int height) {}
            public void surfaceDestroyed(SurfaceHolder h) { if(helper!=null) helper.removeSurface(h.getSurface()); }
        });
    }
    private TextView text(String value,int size) { TextView t=new TextView(this); t.setText(value); t.setTextColor(0xffe8eff5); t.setTextSize(size); t.setPadding(0,8,0,8); return t; }
    private void say(String value) { status.setText(value); android.util.Log.i("UsbCameraLab",value); }
    private boolean video(UsbDevice d) { for(int i=0;i<d.getInterfaceCount();i++) if(d.getInterface(i).getInterfaceClass()==14) return true; return false; }
    private void connect() {
        if(opened || connecting) return;
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.CAMERA},1); return; }
        if(helper==null) { helper=new CameraHelper(); helper.setStateCallback(callback); }
        List<UsbDevice> devices=helper.getDeviceList();
        if(devices==null || devices.isEmpty()) { say("לא נמצאה מצלמה. בדוק חיבור ונסה שוב."); return; }
        for(UsbDevice d:devices) if(video(d)) { selected=d; connecting=true; say("מבקש גישה למצלמה…"); helper.selectDevice(d); return; }
        say("לא נמצא התקן וידאו USB");
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) { super.onRequestPermissionsResult(request,permissions,results); if(request==1 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) connect(); else say("נדרשת הרשאת מצלמה כדי להציג וידאו"); }
    private final ICameraHelper.StateCallback callback=new ICameraHelper.StateCallback() {
        public void onAttach(UsbDevice d) { if(!opened && !connecting) say("מצלמה זמינה — לחץ על התחברות"); }
        public void onDeviceOpen(UsbDevice d,boolean first) { if(foreground && connecting && helper!=null) helper.openCamera(); }
        public void onCameraOpen(UsbDevice d) {
            if(helper==null) return;
            if(!foreground || !connecting) { helper.closeCamera(); return; }
            opened=true; connecting=false; selected=d;
            helper.startPreview();
            if(preview.getHolder().getSurface().isValid()) helper.addSurface(preview.getHolder().getSurface(),false);
            Size s=helper.getPreviewSize();
            if(s!=null) { aspect=(double)s.width/s.height; preview.getHolder().setFixedSize(s.width,s.height); fitPreview(); }
            say("המצלמה פתוחה • תצוגה פעילה");
            details.setText(d.getProductName()+(s==null?"":" • "+s.width+" × "+s.height));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            ui.postDelayed(() -> showControls(),700);
        }
        public void onCameraClose(UsbDevice d) { closed("המצלמה סגורה"); }
        public void onDeviceClose(UsbDevice d) { closed("החיבור נסגר"); }
        public void onDetach(UsbDevice d) { if(selected!=null && selected.equals(d)) closed("המצלמה נותקה מה־USB"); }
        public void onCancel(UsbDevice d) { connecting=false; say("הגישה למצלמה לא אושרה. אפשר לנסות שוב."); }
        public void onError(UsbDevice d,CameraException e) { closed("לא ניתן לפתוח את המצלמה. נתק וחבר שוב."); android.util.Log.e("UsbCameraLab","Camera error",e); }
    };
    private void fitPreview() {
        int w=previewBox.getWidth(),h=previewBox.getHeight(); if(w==0 || h==0)return;
        if(w/(double)h>aspect) w=(int)(h*aspect); else h=(int)(w/aspect);
        android.view.ViewGroup.LayoutParams old=preview.getLayoutParams();
        if(old.width!=w || old.height!=h) preview.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));
    }
    private void showControls() {
        controls.removeAllViews(); if(!opened || helper==null) return;
        UVCControl c=helper.getUVCControl(); if(c==null) return;
        if(c.checkSupportFlag(UVCControl.PU_BRIGHTNESS_CONTROL)) slider("בהירות",c.getBrightnessPercent(),true);
        if(c.checkSupportFlag(UVCControl.PU_CONTRAST_CONTROL)) slider("ניגודיות",c.getContrastPercent(),false);
        if(controls.getChildCount()==0) controls.addView(text("המצלמה אינה מדווחת על שליטה בבהירות או בניגודיות",14));
    }
    private void slider(String label,int initial,boolean brightness) {
        controls.addView(text(label,14)); SeekBar bar=new SeekBar(this); bar.setMax(100); bar.setProgress(initial); controls.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {}
            public void onProgressChanged(SeekBar b,int value,boolean user) {}
            public void onStopTrackingTouch(SeekBar b) {
                if(!opened || helper==null) return; UVCControl c=helper.getUVCControl(); if(c==null)return;
                if(brightness)c.setBrightnessPercent(b.getProgress()); else c.setContrastPercent(b.getProgress());
            }
        });
    }
    private void closed(String message) { opened=false; connecting=false; controls.removeAllViews(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); say(message); }
    private void disconnect() { ui.removeCallbacksAndMessages(null); if(helper!=null) { helper.stopPreview(); helper.closeCamera(); } closed("מנותק — לחץ על התחברות כדי להתחיל"); }
    @Override protected void onStart() { super.onStart(); foreground=true; }
    @Override protected void onStop() { foreground=false; disconnect(); super.onStop(); }
    @Override protected void onDestroy() { if(helper!=null) { helper.setStateCallback(null); helper.release(); helper=null; } super.onDestroy(); }
}
