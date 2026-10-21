package com.anxel.usbcameralab;

import static com.serenegiant.usb.UVCControl.*;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.herohan.uvcapp.*;
import com.serenegiant.usb.*;
import com.serenegiant.utils.UVCUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// English-only UI built in code (no string resources), with framework widgets on a framework theme (no AppCompat).
@SuppressLint({"SetTextI18n","UseSwitchCompatOrMaterialCode"})
public class MainActivity extends Activity {
    // The camera connects by itself while the app is on screen (on start, and when a camera is plugged in) and stops when
    // the app leaves the screen or the camera is unplugged. After a failure the user decides: Try again, or replug.
    // CLOSING: closing an opened camera also closes its USB device (UVCCamera.close), and that onDeviceClose arrives
    // a moment later. Retrying waits until then, so the late event can't land on the next attempt.
    private enum State { IDLE, CONNECTING, OPEN, CLOSING }
    private static final String IDLE_DETAILS = "Local preview • no recording";
    private static final String NO_CAMERA = "Plug in a USB camera";
    private static final int TEXT = 0xffe8eff5, MUTED = 0xff9aa8b4, ACCENT = 0xff7fc4e0;
    // One CameraHelper per foreground session (onStart..onStop); events from a released helper are ignored.
    private CameraHelper helper;
    private State state = State.IDLE;
    // The USB device is already open in this helper: reconnect with openCamera(). Calling selectDevice() on an
    // open device makes UVCAndroid register its camera callbacks again, so every later event arrives twice.
    private boolean deviceOpen;
    private boolean waitForUser; // last attempt failed or was declined: no automatic retry until Try again, a replug or onStart
    private boolean askingPermission; // camera permission dialog is up
    private String closingMessage;
    private UsbManager usb;
    private UsbDevice selected;
    private UVCControl control;
    private final List<Row> rows = new ArrayList<>();
    private final Map<Row,Integer> pendingWrites = new LinkedHashMap<>(); // slider values waiting to be sent
    private boolean writeQueued, updating;
    private boolean landscape;
    private LinearLayout root, header, panel, controls;
    private FrameLayout previewBox;
    private SurfaceView preview;
    private double aspect = 4.0/3.0;
    private TextView status, details;
    private Button action; // the one button, shown only when the user has to act
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        UVCUtils.init(getApplication());
        usb=(UsbManager)getSystemService(USB_SERVICE);
        root=new LinearLayout(this); root.setPadding(dp(12),dp(12),dp(12),dp(8)); root.setBackgroundColor(0xff101820);
        header=new LinearLayout(this); header.setOrientation(LinearLayout.VERTICAL);
        header.addView(text("USB Camera Lab",22));
        status=text(NO_CAMERA,15); header.addView(status);
        action=new Button(this); action.setVisibility(View.GONE); header.addView(action,new LinearLayout.LayoutParams(-2,-2));
        previewBox=new FrameLayout(this);
        preview=new SurfaceView(this); previewBox.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        previewBox.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob) -> fitPreview());
        panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        details=text(IDLE_DETAILS,14); panel.addView(details);
        controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(this); scroll.addView(controls,new FrameLayout.LayoutParams(-1,-2));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(previewBox); root.addView(panel);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob) -> root.post(this::sizePreview));
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
        landscape=c.orientation==Configuration.ORIENTATION_LANDSCAPE;
        ViewGroup parent=(ViewGroup)header.getParent(); if(parent!=null) parent.removeView(header);
        if(landscape) {
            panel.addView(header,0,new LinearLayout.LayoutParams(-1,-2)); root.setOrientation(LinearLayout.HORIZONTAL);
            previewBox.setLayoutParams(new LinearLayout.LayoutParams(0,-1,3));
            panel.setLayoutParams(new LinearLayout.LayoutParams(0,-1,2)); panel.setPaddingRelative(dp(12),0,0,0);
        } else {
            root.addView(header,0,new LinearLayout.LayoutParams(-1,-2)); root.setOrientation(LinearLayout.VERTICAL);
            previewBox.setLayoutParams(new LinearLayout.LayoutParams(-1,0));
            panel.setLayoutParams(new LinearLayout.LayoutParams(-1,0,1)); panel.setPaddingRelative(0,0,0,0);
            root.post(this::sizePreview);
        }
    }
    // Portrait: the preview gets the full width at the camera's aspect ratio (at most 55% of the height); controls scroll below.
    private void sizePreview() {
        if(landscape) return;
        int w=root.getWidth()-root.getPaddingLeft()-root.getPaddingRight(), h=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom();
        if(w<=0 || h<=0) return;
        int target=Math.min((int)Math.round(w/aspect),h*55/100);
        ViewGroup.LayoutParams lp=previewBox.getLayoutParams();
        if(lp.height!=target) { lp.height=target; previewBox.setLayoutParams(lp); }
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView text(String value,int size) { TextView t=new TextView(this); t.setText(value); t.setTextColor(TEXT); t.setTextSize(size); t.setPadding(0,dp(4),0,dp(4)); return t; }
    private void say(String value) { status.setText(value); android.util.Log.i("UsbCameraLab",value); }
    private boolean video(UsbDevice d) { for(int i=0;i<d.getInterfaceCount();i++) if(d.getInterface(i).getInterfaceClass()==UsbConstants.USB_CLASS_VIDEO) return true; return false; }
    private void setState(State next) {
        state=next; android.util.Log.i("UsbCameraLab","state "+next);
        if(next==State.OPEN) { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); return; }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacksAndMessages(null); writeQueued=false; pendingWrites.clear();
        control=null; rows.clear(); controls.removeAllViews(); details.setText(IDLE_DETAILS);
    }
    private UsbDevice findCamera() {
        List<UsbDevice> devices=helper==null?null:helper.getDeviceList();
        if(devices!=null) for(UsbDevice d:devices) if(video(d)) return d;
        return null;
    }
    private void connect() {
        if(state!=State.IDLE || helper==null || askingPermission) return;
        UsbDevice d=deviceOpen && selected!=null ? selected : findCamera();
        if(d==null) { hideAction(); say(NO_CAMERA); return; }
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { askingPermission=true; requestPermissions(new String[]{Manifest.permission.CAMERA},1); return; }
        waitForUser=false; hideAction();
        if(deviceOpen && selected!=null) { setState(State.CONNECTING); say("Opening camera…"); helper.openCamera(); return; }
        selected=d; setState(State.CONNECTING);
        say(usb.hasPermission(d)?"Connecting…":"Allow USB access to the camera…");
        helper.selectDevice(d);
    }
    // The user has to act: show why, and the one button that helps.
    private void needUser(String message) { waitForUser=true; say(message); showAction("Try again",this::retry); }
    private void retry() { waitForUser=false; connect(); }
    private void showAction(String label,Runnable onTap) { action.setText(label); action.setOnClickListener(v -> onTap.run()); action.setVisibility(View.VISIBLE); }
    private void hideAction() { action.setVisibility(View.GONE); }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=1) return;
        askingPermission=false;
        if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) { connect(); return; }
        // Denied with no rationale: Android won't show the dialog again (or it was dismissed), so offer the app's settings page.
        if(results.length>0 && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            waitForUser=true; say("Camera permission is off. Allow it in app settings to see the camera.");
            showAction("Open app settings",() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",getPackageName(),null))));
        } else needUser("Camera permission is needed to show the camera.");
    }
    // Android's USB attach dialog ("Open USB Camera Lab?") granted access and handed the camera to us.
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) retry();
    }
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
            showAction("Try again",MainActivity.this::retry);
        }
        public void onDeviceOpen(UsbDevice d,boolean first) {
            if(stale()) return;
            deviceOpen=true; selected=d;
            if(state==State.CONNECTING) { say("Opening camera…"); owner.openCamera(); }
        }
        public void onCameraOpen(UsbDevice d) {
            if(stale()) return;
            if(state!=State.CONNECTING) { if(state==State.IDLE) owner.closeCamera(); return; } // CLOSING: our closeCamera() is already queued
            setState(State.OPEN); selected=d;
            owner.startPreview();
            if(preview.getHolder().getSurface().isValid()) owner.addSurface(preview.getHolder().getSurface(),false);
            Size s=owner.getPreviewSize();
            if(s!=null) { aspect=(double)s.width/s.height; preview.getHolder().setFixedSize(s.width,s.height); fitPreview(); sizePreview(); }
            String name=d.getProductName();
            details.setText((name==null?"USB camera":name)+(s==null?"":" • "+s.width+" × "+s.height));
            say("Camera open • preview running");
            ui.postDelayed(MainActivity.this::showControls,700); // UVCControl: support flags can be wrong right after open
        }
        public void onCameraClose(UsbDevice d) {
            if(stale()) return;
            deviceOpen=false;
            if(state==State.OPEN) closing("The camera stopped.");
        }
        public void onDeviceClose(UsbDevice d) {
            if(stale()) return;
            deviceOpen=false;
            if(state==State.CLOSING) { setState(State.IDLE); needUser(closingMessage); }
            else if(state!=State.IDLE) { setState(State.IDLE); needUser("Lost the camera connection."); }
        }
        public void onDetach(UsbDevice d) {
            if(stale()) return;
            boolean ours=selected!=null && selected.equals(d);
            if(ours) { deviceOpen=false; selected=null; if(state!=State.IDLE) setState(State.IDLE); }
            if(ours || (state==State.IDLE && video(d))) { waitForUser=false; hideAction(); say("Camera unplugged. Plug it back in to continue."); }
        }
        public void onCancel(UsbDevice d) { if(!stale() && state==State.CONNECTING) { setState(State.IDLE); needUser("USB access to the camera was not allowed."); } }
        public void onError(UsbDevice d,CameraException e) {
            android.util.Log.e("UsbCameraLab","Camera error",e);
            if(!stale() && state!=State.IDLE) { deviceOpen=false; setState(State.IDLE); needUser("Couldn't open the camera. Try again, or unplug it and plug it back in."); }
        }
    }
    private void fitPreview() {
        int w=previewBox.getWidth(),h=previewBox.getHeight(); if(w==0 || h==0)return;
        if(w/(double)h>aspect) w=(int)(h*aspect); else h=(int)(w/aspect);
        android.view.ViewGroup.LayoutParams old=preview.getLayoutParams();
        if(old.width!=w || old.height!=h) preview.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));
    }

    // ---- Camera controls: every UVC control UVCAndroid can drive, shown only when the camera reports it ----
    private static final int RANGE=0, TOGGLE=1, CHOICE=2;
    private interface Getter { int get(UVCControl c); }
    private interface Setter { void set(UVCControl c,int value); }
    private interface Limits { int[] get(UVCControl c); }
    private interface Resetter { void reset(UVCControl c); }
    private interface Units { String show(int value); }
    // TOGGLE values are 0/1 and CHOICE values index `choices`; a RANGE with autoFlag is disabled while that toggle is on.
    private static final class Spec {
        final String section,label; final int flag,kind,autoFlag; final Limits limits; final Getter get; final Setter set; final Resetter reset; final Units units; final String[] choices;
        Spec(String section,String label,int flag,int kind,int autoFlag,Limits limits,Getter get,Setter set,Resetter reset,Units units,String[] choices) {
            this.section=section; this.label=label; this.flag=flag; this.kind=kind; this.autoFlag=autoFlag; this.limits=limits; this.get=get; this.set=set; this.reset=reset; this.units=units; this.choices=choices;
        }
    }
    private static Spec range(String section,String label,int flag,int autoFlag,Limits limits,Getter get,Setter set,Resetter reset,Units units) { return new Spec(section,label,flag,RANGE,autoFlag,limits,get,set,reset,units,null); }
    private static Spec toggle(String section,String label,int flag,Getter get,Setter set,Resetter reset) { return new Spec(section,label,flag,TOGGLE,0,null,get,set,reset,null,null); }
    private static Spec choice(String section,String label,int flag,Limits limits,Getter get,Setter set,Resetter reset,String... choices) { return new Spec(section,label,flag,CHOICE,0,limits,get,set,reset,null,choices); }
    private static final Units RAW=String::valueOf;
    private static final String IMAGE="Image", WB="White balance", EXPOSURE="Exposure", OPTICS="Focus, zoom and position", OTHER="Other";
    private static final Spec[] SPECS={
        range(IMAGE,"Brightness",PU_BRIGHTNESS_CONTROL,0,UVCControl::updateBrightnessLimit,UVCControl::getBrightness,UVCControl::setBrightness,UVCControl::resetBrightness,RAW),
        toggle(IMAGE,"Auto contrast",PU_CONTRAST_AUTO_CONTROL,c -> c.getContrastAuto()?1:0,(c,v) -> c.setContrastAuto(v!=0),UVCControl::resetContrastAuto),
        range(IMAGE,"Contrast",PU_CONTRAST_CONTROL,PU_CONTRAST_AUTO_CONTROL,UVCControl::updateContrastLimit,UVCControl::getContrast,UVCControl::setContrast,UVCControl::resetContrast,RAW),
        toggle(IMAGE,"Auto hue",PU_HUE_AUTO_CONTROL,c -> c.getHueAuto()?1:0,(c,v) -> c.setHueAuto(v!=0),UVCControl::resetHueAuto),
        range(IMAGE,"Hue",PU_HUE_CONTROL,PU_HUE_AUTO_CONTROL,UVCControl::updateHueLimit,UVCControl::getHue,UVCControl::setHue,UVCControl::resetHue,RAW),
        range(IMAGE,"Saturation",PU_SATURATION_CONTROL,0,UVCControl::updateSaturationLimit,UVCControl::getSaturation,UVCControl::setSaturation,UVCControl::resetSaturation,RAW),
        range(IMAGE,"Sharpness",PU_SHARPNESS_CONTROL,0,UVCControl::updateSharpnessLimit,UVCControl::getSharpness,UVCControl::setSharpness,UVCControl::resetSharpness,RAW),
        range(IMAGE,"Gamma",PU_GAMMA_CONTROL,0,UVCControl::updateGammaLimit,UVCControl::getGamma,UVCControl::setGamma,UVCControl::resetGamma,RAW),
        range(IMAGE,"Gain",PU_GAIN_CONTROL,0,UVCControl::updateGainLimit,UVCControl::getGain,UVCControl::setGain,UVCControl::resetGain,RAW),
        range(IMAGE,"Backlight compensation",PU_BACKLIGHT_COMPENSATION_CONTROL,0,UVCControl::updateBacklightCompLimit,UVCControl::getBacklightComp,UVCControl::setBacklightComp,UVCControl::resetBacklightComp,RAW),
        toggle(WB,"Auto white balance",PU_WHITE_BALANCE_TEMPERATURE_AUTO_CONTROL,c -> c.getWhiteBalanceAuto()?1:0,(c,v) -> c.setWhiteBalanceAuto(v!=0),UVCControl::resetWhiteBalanceAuto),
        range(WB,"Color temperature",PU_WHITE_BALANCE_TEMPERATURE_CONTROL,PU_WHITE_BALANCE_TEMPERATURE_AUTO_CONTROL,UVCControl::updateWhiteBalanceLimit,UVCControl::getWhiteBalance,UVCControl::setWhiteBalance,UVCControl::resetWhiteBalance,v -> v>=1000?v+" K":String.valueOf(v)), // some cameras use a small preset scale, not kelvin
        // Auto exposure on/off maps manual <-> aperture priority (and shutter priority <-> auto), as UVCControl.setExposureTimeAuto does.
        toggle(EXPOSURE,"Auto exposure",CT_AE_MODE_CONTROL,c -> c.isExposureTimeAuto()?1:0,(c,v) -> c.setExposureTimeAuto(v!=0),UVCControl::resetAutoExposureMode),
        range(EXPOSURE,"Exposure time",CT_EXPOSURE_TIME_ABSOLUTE_CONTROL,CT_AE_MODE_CONTROL,UVCControl::updateExposureTimeAbsoluteLimit,UVCControl::getExposureTimeAbsolute,UVCControl::setExposureTimeAbsolute,UVCControl::resetExposureTimeAbsolute,v -> String.format(Locale.US,"%.1f ms",v/10.0)),
        toggle(EXPOSURE,"Allow lower frame rate in low light",CT_AE_PRIORITY_CONTROL,UVCControl::getAutoExposurePriority,UVCControl::setAutoExposurePriority,UVCControl::resetAutoExposurePriority),
        range(EXPOSURE,"Iris",CT_IRIS_ABSOLUTE_CONTROL,0,UVCControl::updateIrisAbsoluteLimit,UVCControl::getIrisAbsolute,UVCControl::setIrisAbsolute,UVCControl::resetIrisAbsolute,v -> String.format(Locale.US,"f/%.1f",v/100.0)),
        choice(EXPOSURE,"Power line frequency (anti-flicker)",PU_POWER_LINE_FREQUENCY_CONTROL,UVCControl::updatePowerlineFrequencyLimit,UVCControl::getPowerlineFrequency,UVCControl::setPowerlineFrequency,UVCControl::resetPowerlineFrequency,"Off","50 Hz","60 Hz","Auto"),
        toggle(OPTICS,"Autofocus",CT_FOCUS_AUTO_CONTROL,c -> c.getFocusAuto()?1:0,(c,v) -> c.setFocusAuto(v!=0),UVCControl::resetFocusAuto),
        range(OPTICS,"Focus",CT_FOCUS_ABSOLUTE_CONTROL,CT_FOCUS_AUTO_CONTROL,UVCControl::updateFocusAbsoluteLimit,UVCControl::getFocusAbsolute,UVCControl::setFocusAbsolute,UVCControl::resetFocusAbsolute,RAW),
        range(OPTICS,"Zoom",CT_ZOOM_ABSOLUTE_CONTROL,0,UVCControl::updateZoomAbsoluteLimit,UVCControl::getZoomAbsolute,UVCControl::setZoomAbsolute,UVCControl::resetZoomAbsolute,RAW),
        range(OPTICS,"Pan",CT_PANTILT_ABSOLUTE_CONTROL,0,UVCControl::updatePanAbsoluteLimit,UVCControl::getPanAbsolute,UVCControl::setPanAbsolute,UVCControl::resetPanAbsolute,v -> String.format(Locale.US,"%.1f°",v/3600.0)),
        range(OPTICS,"Tilt",CT_PANTILT_ABSOLUTE_CONTROL,0,UVCControl::updateTiltAbsoluteLimit,UVCControl::getTiltAbsolute,UVCControl::setTiltAbsolute,UVCControl::resetTiltAbsolute,v -> String.format(Locale.US,"%.1f°",v/3600.0)),
        range(OPTICS,"Roll",CT_ROLL_ABSOLUTE_CONTROL,0,UVCControl::updateRollAbsoluteLimit,UVCControl::getRollAbsolute,UVCControl::setRollAbsolute,UVCControl::resetRollAbsolute,v -> v+"°"),
        range(OPTICS,"Digital zoom",PU_DIGITAL_MULTIPLIER_CONTROL,0,UVCControl::updateDigitalMultiplierLimit,UVCControl::getDigitalMultiplier,UVCControl::setDigitalMultiplier,UVCControl::resetDigitalMultiplier,RAW),
        range(OPTICS,"Digital zoom limit",PU_DIGITAL_MULTIPLIER_LIMIT_CONTROL,0,UVCControl::updateDigitalMultiplierLimitLimit,UVCControl::getDigitalMultiplierLimit,UVCControl::setDigitalMultiplierLimit,UVCControl::resetDigitalMultiplierLimit,RAW),
        toggle(OTHER,"Privacy shutter",CT_PRIVACY_CONTROL,c -> c.getPrivacy()?1:0,(c,v) -> c.setPrivacy(v!=0),UVCControl::resetPrivacy),
        choice(OTHER,"Scanning mode",CT_SCANNING_MODE_CONTROL,UVCControl::updateScanningModeLimit,UVCControl::getScanningMode,UVCControl::setScanningMode,UVCControl::resetScanningMode,"Interlaced","Progressive"),
    };
    // Reported controls with no settings UI here: relative ones are "move at this speed" commands, the rest are packed or analog-only.
    private static final int[] UNLISTED_FLAGS={CT_EXPOSURE_TIME_RELATIVE_CONTROL,CT_FOCUS_RELATIVE_CONTROL,CT_IRIS_RELATIVE_CONTROL,CT_ZOOM_RELATIVE_CONTROL,CT_PANTILT_RELATIVE_CONTROL,CT_ROLL_RELATIVE_CONTROL,CT_FOCUS_SIMPLE_CONTROL,CT_WINDOW_CONTROL,PU_WHITE_BALANCE_COMPONENT_CONTROL,PU_WHITE_BALANCE_COMPONENT_AUTO_CONTROL,PU_ANALOG_VIDEO_STANDARD_CONTROL,PU_ANALOG_LOCK_STATUS_CONTROL};
    private static final String[] UNLISTED_NAMES={"exposure (relative)","focus (relative)","iris (relative)","zoom (relative)","pan/tilt (relative)","roll (relative)","focus (simple)","window","white balance components","auto white balance components","analog video standard","analog lock status"};

    // One control on screen. show() reads the camera and updates the widgets without writing back.
    private final class Row {
        final Spec spec; int min,max; View view; TextView value; SeekBar bar; Switch toggle; RadioGroup group;
        Row(Spec spec) { this.spec=spec; }
        boolean build() {
            if(spec.kind==RANGE) {
                int[] l=spec.limits.get(control); if(l==null || l.length<2 || l[1]<=l[0]) return false;
                min=l[0]; max=l[1];
                LinearLayout box=new LinearLayout(MainActivity.this); box.setOrientation(LinearLayout.VERTICAL);
                LinearLayout line=new LinearLayout(MainActivity.this); box.addView(line);
                line.addView(text(spec.label,14),new LinearLayout.LayoutParams(0,-2,1));
                value=text("",14); value.setTextColor(MUTED); line.addView(value);
                bar=new SeekBar(MainActivity.this); bar.setMax(max-min); box.addView(bar);
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar b) {}
                    public void onProgressChanged(SeekBar b,int p,boolean user) { value.setText(spec.units.show(min+p)); if(user) queueWrite(Row.this,min+p); }
                    public void onStopTrackingTouch(SeekBar b) { queueWrite(Row.this,min+b.getProgress()); scheduleRefresh(); } // show what the camera applied
                });
                view=box;
            } else if(spec.kind==TOGGLE) {
                toggle=new Switch(MainActivity.this); toggle.setText(spec.label); toggle.setTextColor(TEXT); toggle.setTextSize(14); toggle.setPadding(0,dp(8),0,dp(8));
                toggle.setOnCheckedChangeListener((b,on) -> { if(!updating) userChanged(this,on?1:0); });
                view=toggle;
            } else {
                int lo=0, hi=spec.choices.length-1;
                int[] l=spec.limits.get(control); if(l!=null && l.length>=2 && l[1]>l[0]) { lo=Math.max(lo,l[0]); hi=Math.min(hi,l[1]); }
                LinearLayout box=new LinearLayout(MainActivity.this); box.setOrientation(LinearLayout.VERTICAL);
                box.addView(text(spec.label,14));
                group=new RadioGroup(MainActivity.this); group.setOrientation(LinearLayout.HORIZONTAL); box.addView(group);
                for(int v=lo;v<=hi;v++) { RadioButton b=new RadioButton(MainActivity.this); b.setId(View.generateViewId()); b.setTag(v); b.setText(spec.choices[v]); b.setTextColor(TEXT); group.addView(b); }
                group.setOnCheckedChangeListener((g,id) -> { View b=g.findViewById(id); if(!updating && b!=null) userChanged(this,(Integer)b.getTag()); });
                view=box;
            }
            show(); return true;
        }
        void show() {
            int v=spec.get.get(control);
            updating=true;
            if(bar!=null) { int c=Math.max(min,Math.min(max,v)); bar.setProgress(c-min); value.setText(spec.units.show(c)); }
            else if(toggle!=null) toggle.setChecked(v!=0);
            else for(int i=0;i<group.getChildCount();i++) if((Integer)group.getChildAt(i).getTag()==v) group.check(group.getChildAt(i).getId());
            updating=false;
        }
        String describe() {
            if(bar!=null) return spec.label+" ["+min+".."+max+"]="+(min+bar.getProgress());
            if(toggle!=null) return spec.label+"="+toggle.isChecked();
            View b=group.findViewById(group.getCheckedRadioButtonId()); return spec.label+"="+(b==null?"?":b.getTag());
        }
    }
    private void showControls() {
        controls.removeAllViews(); rows.clear(); if(state!=State.OPEN) return;
        control=helper.getUVCControl(); if(control==null) return;
        long started=SystemClock.uptimeMillis();
        List<View> views=new ArrayList<>(); String section=null;
        for(Spec s:SPECS) {
            if(!control.checkSupportFlag(s.flag)) continue;
            Row r=new Row(s);
            if(!r.build()) { android.util.Log.w("UsbCameraLab","control "+s.label+" reported but has no usable range"); continue; }
            if(!s.section.equals(section)) { section=s.section; TextView h=text(section,13); h.setTextColor(ACCENT); h.setTypeface(Typeface.DEFAULT_BOLD); h.setPadding(0,dp(12),0,dp(2)); views.add(h); }
            views.add(r.view); rows.add(r);
        }
        StringBuilder unlisted=new StringBuilder();
        for(int i=0;i<UNLISTED_FLAGS.length;i++) if(control.checkSupportFlag(UNLISTED_FLAGS[i])) unlisted.append(unlisted.length()==0?"":", ").append(UNLISTED_NAMES[i]);
        if(rows.isEmpty()) controls.addView(text("The camera reports no adjustable controls",14));
        else { Button reset=new Button(this); reset.setText("Reset all to defaults"); reset.setOnClickListener(v -> resetAll()); controls.addView(reset,new LinearLayout.LayoutParams(-2,-2)); }
        for(View v:views) controls.addView(v);
        if(unlisted.length()>0) { TextView note=text("Also reported, not adjustable here: "+unlisted,12); note.setTextColor(MUTED); note.setPadding(0,dp(12),0,0); controls.addView(note); }
        updateEnabled();
        StringBuilder log=new StringBuilder();
        for(Row r:rows) log.append(log.length()==0?"":"; ").append(r.describe());
        android.util.Log.i("UsbCameraLab","controls ("+(SystemClock.uptimeMillis()-started)+" ms): "+log+(unlisted.length()>0?" | unlisted: "+unlisted:""));
    }
    // A slider disabled while its auto mode is on: the camera picks that value itself.
    private void updateEnabled() {
        for(Row r:rows) if(r.bar!=null && r.spec.autoFlag!=0) {
            boolean auto=false;
            for(Row t:rows) if(t.toggle!=null && t.spec.flag==r.spec.autoFlag) auto=t.toggle.isChecked();
            r.bar.setEnabled(!auto); r.view.setAlpha(auto?0.45f:1f);
        }
    }
    // Toggles and choices are written at once, then everything is re-read: the camera may refuse a value or move others.
    private void userChanged(Row row,int value) {
        if(state!=State.OPEN || control==null) return;
        row.spec.set.set(control,value);
        updateEnabled(); scheduleRefresh();
    }
    private void resetAll() {
        if(state!=State.OPEN || control==null) return;
        pendingWrites.clear();
        for(Row r:rows) if(r.spec.kind==RANGE) r.spec.reset.reset(control);
        for(Row r:rows) if(r.spec.kind!=RANGE) r.spec.reset.reset(control); // auto modes last, so they win over manual values
        scheduleRefresh();
    }
    private final Runnable refresh=() -> {
        if(state!=State.OPEN || control==null) return;
        long started=SystemClock.uptimeMillis(); StringBuilder log=new StringBuilder();
        for(Row r:rows) { r.show(); log.append(log.length()==0?"":"; ").append(r.describe()); }
        updateEnabled();
        android.util.Log.i("UsbCameraLab","refresh ("+(SystemClock.uptimeMillis()-started)+" ms): "+log);
    };
    private void scheduleRefresh() { ui.removeCallbacks(refresh); ui.postDelayed(refresh,400); }
    // Live sliders: coalesce drag updates and send at most one UVC control write per slider every 50 ms.
    private void queueWrite(Row row,int value) {
        pendingWrites.put(row,value);
        if(!writeQueued) { writeQueued=true; ui.postDelayed(applyWrites,50); }
    }
    private final Runnable applyWrites=() -> {
        writeQueued=false;
        if(state==State.OPEN && control!=null) for(Map.Entry<Row,Integer> e:pendingWrites.entrySet()) e.getKey().spec.set.set(control,e.getValue());
        pendingWrites.clear();
    };
    private void closing(String message) {
        setState(State.CLOSING); closingMessage=message; say("Closing…");
        ui.postDelayed(() -> { if(state==State.CLOSING) { setState(State.IDLE); needUser(closingMessage); } },3000); // fallback if onDeviceClose never comes
    }
    @Override protected void onStart() {
        super.onStart();
        helper=new CameraHelper(); helper.setStateCallback(new Events(helper));
        waitForUser=false; hideAction();
        connect(); // a camera that is already plugged in; later ones arrive through onAttach
    }
    @Override protected void onStop() {
        // release() closes the camera and the USB device. Don't call setStateCallback(null) first: that drops the
        // USB monitor, and release() could no longer close the device.
        if(helper!=null) { helper.release(); helper=null; }
        if(state!=State.IDLE) setState(State.IDLE);
        deviceOpen=false; selected=null;
        super.onStop();
    }
}
