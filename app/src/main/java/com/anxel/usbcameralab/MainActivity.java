package com.anxel.usbcameralab;

import static com.anxel.usbcameralab.CameraSession.at;
import static com.anxel.usbcameralab.CameraSession.cameraKey;
import static com.anxel.usbcameralab.CameraSession.format;
import static com.anxel.usbcameralab.CameraSession.modeName;
import static com.anxel.usbcameralab.CameraSession.rate;
import static com.anxel.usbcameralab.CameraSession.same;
import static com.anxel.usbcameralab.CameraSession.shortName;
import static com.serenegiant.usb.UVCControl.*;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.anxel.usbcameralab.CameraSession.State;
import com.serenegiant.usb.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// English-only UI built in code (no string resources), on a Material 3 theme (AppCompatActivity).
@SuppressLint({"SetTextI18n","UseSwitchCompatOrMaterialCode"})
public class MainActivity extends AppCompatActivity {
    // The camera, its connection state machine, the stream mode and the capture live in CameraSession, for the whole process.
    // This screen attaches while it is on screen (onStart..onStop): it renders the session, adds its preview surface, and builds
    // the controls panel and the capture bar on the session's camera. A video or time-lapse goes on while the screen is away
    // (CaptureService); coming back shows it as it is, with no reconnect.
    private static final String IDLE_DETAILS = "Local preview";
    private static final int TEXT = 0xffe8eff5, MUTED = 0xff9aa8b4, ACCENT = 0xff7fc4e0, BACKGROUND = 0xff101820;
    private CameraSession session;
    private CaptureController capture; // the session's
    private UVCControl control;
    private final List<Row> rows = new ArrayList<>();
    private final Map<Row,Integer> pendingWrites = new LinkedHashMap<>(); // slider values waiting to be sent
    private boolean writeQueued, updating;
    private Spinner sizePicker, ratePicker; // off while a capture is busy: a new stream size would change the aspect under a running encoder
    private boolean landscape, fullScreen;
    private LinearLayout root, header, panel, controls;
    private FrameLayout previewBox;
    private SurfaceView preview;
    private double aspect = 4.0/3.0;
    private Insets bars=Insets.NONE; // system bars and cutout: the window is drawn edge-to-edge, behind them
    private TextView status, details;
    private Button action; // the one button, shown only when the user has to act
    private CaptureBar captureBar;
    private AlertDialog restoreDialog;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override public void onCreate(Bundle saved) {
        EdgeToEdge.enable(this,SystemBarStyle.dark(0),SystemBarStyle.dark(0)); // transparent bars with light icons
        super.onCreate(saved);
        session=CameraSession.get(this); capture=session.capture;
        root=new LinearLayout(this); root.setBackgroundColor(BACKGROUND);
        header=new LinearLayout(this); header.setOrientation(LinearLayout.VERTICAL);
        header.addView(text("USB Camera Lab",22));
        status=text(CameraSession.NO_CAMERA,15); header.addView(status);
        action=new Button(this); action.setVisibility(View.GONE); header.addView(action,new LinearLayout.LayoutParams(-2,-2));
        previewBox=new FrameLayout(this);
        preview=new SurfaceView(this); previewBox.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        captureBar=new CaptureBar(this,captureHost); captureBar.attach(capture);
        previewBox.addView(captureBar.flash,new FrameLayout.LayoutParams(-1,-1)); previewBox.addView(captureBar.bar);
        FrameLayout.LayoutParams top=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL); top.topMargin=dp(10);
        previewBox.addView(captureBar.info,top);
        previewBox.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob) -> fitPreview());
        previewBox.setOnClickListener(v -> setFullScreen(!fullScreen && session.state()==State.OPEN));
        previewBox.setContentDescription("Camera preview. Tap to enter or leave full screen.");
        panel=new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        details=text(IDLE_DETAILS,14); panel.addView(details);
        controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(this); scroll.addView(controls,new FrameLayout.LayoutParams(-1,-2));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(previewBox); root.addView(panel);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,orr,ob) -> root.post(this::sizePreview));
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets) -> { bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()); arrange(getResources().getConfiguration()); return insets; });
        getOnBackPressedDispatcher().addCallback(this,back);
        arrange(getResources().getConfiguration());
        setContentView(root);
        addContentView(captureBar.dim,new ViewGroup.LayoutParams(-1,-1)); // over everything while a time-lapse runs dimmed
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { session.setPreview(h.getSurface()); }
            public void surfaceChanged(SurfaceHolder h,int f,int w,int height) {}
            public void surfaceDestroyed(SurfaceHolder h) { session.setPreview(null); }
        });
    }
    // Rotation arrives here (manifest configChanges), so the stream keeps running; landscape puts the controls beside the preview.
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); arrange(c); }
    private void arrange(Configuration c) {
        landscape=c.orientation==Configuration.ORIENTATION_LANDSCAPE;
        captureBar.arrange(landscape);
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
        if(fullScreen) { previewBox.setLayoutParams(new LinearLayout.LayoutParams(-1,-1)); root.setPadding(bars.left,bars.top,bars.right,bars.bottom); }
        else root.setPadding(bars.left+dp(12),bars.top+dp(12),bars.right+dp(12),bars.bottom+dp(8));
    }
    // Full screen: only the preview, letterboxed on black, with the system bars hidden (a swipe shows them for a moment).
    // A tap or Back returns. Only views change, so the stream keeps running.
    @SuppressWarnings("deprecation") // system UI flags: the only way before Android 11 (minSdk 29)
    private void setFullScreen(boolean on) {
        if(on==fullScreen) return;
        fullScreen=on;
        header.setVisibility(on?View.GONE:View.VISIBLE); panel.setVisibility(on?View.GONE:View.VISIBLE);
        root.setBackgroundColor(on?0xff000000:BACKGROUND);
        arrange(getResources().getConfiguration());
        if(Build.VERSION.SDK_INT>=30) {
            WindowInsetsController bars=getWindow().getInsetsController();
            if(bars==null) return;
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if(on) bars.hide(WindowInsets.Type.systemBars()); else bars.show(WindowInsets.Type.systemBars());
        } else getWindow().getDecorView().setSystemUiVisibility(on?View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY:0);
    }
    // Back wakes a dimmed screen, then leaves full screen, then leaves the app as usual.
    private final OnBackPressedCallback back=new OnBackPressedCallback(true) {
        @Override public void handleOnBackPressed() {
            if(captureBar.wake()) return;
            if(fullScreen) { setFullScreen(false); return; }
            setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true);
        }
    };
    // Portrait: the preview gets the full width at the camera's aspect ratio (at most 55% of the height); controls scroll below.
    private void sizePreview() {
        if(landscape || fullScreen) return;
        int w=root.getWidth()-root.getPaddingLeft()-root.getPaddingRight(), h=root.getHeight()-root.getPaddingTop()-root.getPaddingBottom();
        if(w<=0 || h<=0) return;
        int target=Math.min((int)Math.round(w/aspect),h*55/100);
        ViewGroup.LayoutParams lp=previewBox.getLayoutParams();
        if(lp.height!=target) { lp.height=target; previewBox.setLayoutParams(lp); }
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private TextView text(String value,int size) { TextView t=new TextView(this); t.setText(value); t.setTextColor(TEXT); t.setTextSize(size); t.setPadding(0,dp(4),0,dp(4)); return t; }
    private TextView heading(String value) { TextView h=text(value,13); h.setTextColor(ACCENT); h.setTypeface(Typeface.DEFAULT_BOLD); h.setPadding(0,dp(12),0,dp(2)); return h; }
    // The session's side of this screen.
    private final CameraSession.Ui screen=new CameraSession.Ui() {
        public void stateChanged(State next) {
            if(next==State.OPEN) { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); return; }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            ui.removeCallbacksAndMessages(null); writeQueued=false; pendingWrites.clear();
            control=null; rows.clear(); controls.removeAllViews(); sizePicker=ratePicker=null; details.setText(IDLE_DETAILS);
            setFullScreen(false); // messages and Try again live outside the preview
        }
        public void status(String message) { status.setText(message); }
        public void showAction(String label,Runnable onTap) {
            if(label==null) { action.setVisibility(View.GONE); return; }
            action.setText(label); action.setOnClickListener(v -> onTap.run()); action.setVisibility(View.VISIBLE);
        }
        public void askCameraPermission() { requestPermissions(new String[]{Manifest.permission.CAMERA},1); }
        public void opened() { showMode(); ui.postDelayed(MainActivity.this::showControls,700); } // UVCControl: support flags can be wrong right after open
        public void modeChanged() { showMode(); scheduleRefresh(); } // some cameras reset controls with the stream
        public void modePicked(Size m) { movePicker(m); }
    };
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==CaptureBar.MIC_REQUEST) { captureBar.micAnswered(results); return; }
        if(request==CaptureBar.NOTIFY_REQUEST) { captureBar.notifyAnswered(results); return; }
        if(request!=1) return;
        boolean granted=results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED;
        session.permissionAnswered(granted);
        if(granted) return;
        // Denied with no rationale: Android won't show the dialog again (or it was dismissed), so offer the app's settings page.
        if(results.length>0 && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
            session.needUser("Camera permission is off. Allow it in app settings to see the camera.","Open app settings",() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",getPackageName(),null))));
        else session.needUser("Camera permission is needed to show the camera.");
    }
    // Android's USB attach dialog ("Open USB Camera Lab?") granted access and handed the camera to us.
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if(UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) session.retry();
    }
    private void fitPreview() {
        int w=previewBox.getWidth(),h=previewBox.getHeight(); if(w==0 || h==0)return;
        if(w/(double)h>aspect) w=(int)(h*aspect); else h=(int)(w/aspect);
        android.view.ViewGroup.LayoutParams old=preview.getLayoutParams();
        if(old.width!=w || old.height!=h) preview.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));
    }

    // ---- Stream mode: the session's format, frame size and frame rate, and the picker for them ----
    private void showMode() {
        Size mode=session.mode(); UsbDevice d=session.selected();
        if(mode!=null) { aspect=(double)mode.width/mode.height; preview.getHolder().setFixedSize(mode.width,mode.height); fitPreview(); sizePreview(); }
        String name=d==null?null:d.getProductName();
        details.setText((name==null?"USB camera":name)+(mode==null?"":" • "+modeName(mode)));
    }
    // A switch from outside the picker (the capture hints): the picker shows the new mode too. Its listeners fire for the new
    // selection and find the mode already applied.
    private void switchMode(Size m) { session.applyMode(m,true); movePicker(m); }
    private void movePicker(Size m) {
        List<Size> modes=session.modes();
        if(sizePicker==null || ratePicker==null || !same(m,session.mode())) return;
        for(int i=0;i<modes.size();i++) { Size o=modes.get(i); if(o.type==m.type && o.width==m.width && o.height==m.height) sizePicker.setSelection(i,false); }
        showRates(ratePicker);
    }
    private interface Picked { void at(int position); }
    private Spinner spinner(Picked picked) {
        Spinner s=new Spinner(this);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // also fires for setSelection and new adapters: applyMode ignores the current mode
            public void onItemSelected(AdapterView<?> a,View v,int i,long id) { if(session.state()==State.OPEN && session.mode()!=null) picked.at(i); }
            public void onNothingSelected(AdapterView<?> a) {}
        });
        return s;
    }
    private void fill(Spinner s,List<String> labels,int selected) {
        ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a); s.setSelection(selected,false);
    }
    // "Camera mode" heads the panel: format and frame size, then a frame-rate list when that size offers more than one.
    private void addModePicker() {
        Size mode=session.mode(); List<Size> modes=session.modes();
        if(mode==null || modes.isEmpty()) return;
        Spinner rates=spinner(i -> { Size now=session.mode(), res=session.find(now.type,now.width,now.height); if(res!=null) session.applyMode(at(res,res.fpsList.get(i)),true); });
        Spinner sizes=spinner(i -> { Size res=modes.get(i); session.applyMode(at(res,rate(res,session.mode().fps)),true); showRates(rates); });
        List<String> labels=new ArrayList<>(); int current=0;
        for(Size m:modes) {
            if(m.type==mode.type && m.width==mode.width && m.height==mode.height) current=labels.size();
            int lowest=m.fpsList.get(m.fpsList.size()-1);
            labels.add(m.width+" × "+m.height+" "+format(m)+" • "+(lowest==m.fps?"":lowest+"–")+m.fps+" fps");
        }
        fill(sizes,labels,current);
        controls.addView(heading("Camera mode"));
        controls.addView(sizes,new LinearLayout.LayoutParams(-1,-2)); controls.addView(rates,new LinearLayout.LayoutParams(-2,-2));
        showRates(rates);
        sizePicker=sizes; ratePicker=rates; updatePicker();
    }
    private final CaptureBar.Host captureHost=new CaptureBar.Host() {
        public void busyChanged() { updatePicker(); }
        public CaptureBar.Hint hint(CaptureController.Mode m) { return modeHint(m); }
        public boolean canLock() { for(Row r:rows) if(r.toggle!=null && lockable(r.spec)) return true; return false; }
        public void lockAuto() { MainActivity.this.lockAuto(); }
        public void runEnded() { offerRestore(); }
    };
    // Video is 1920 × 1080 at most, and decoding a larger stream is slow (4K: about 11 fps on the A20, so 4K video is choppy).
    // A larger stream, or one under 24 fps, gets a one-tap switch to the mode the picker starts with: the largest MJPEG size
    // within 1920 × 1080 at up to 30 fps. A time-lapse keeps one frame a second at most, so it gets the largest size within
    // 1920 × 1080 at the lowest frame rate the camera offers for it: fewer frames to decode keep the phone cooler and the battery
    // going on long runs. Only a suggestion: photos may want 4K, and a smaller size is the user's choice.
    private CaptureBar.Hint modeHint(CaptureController.Mode m) {
        Size mode=session.mode(); List<Size> modes=session.modes();
        if(session.state()!=State.OPEN || mode==null || modes.isEmpty()) return null;
        boolean big=mode.width>1920 || mode.height>1080;
        if(m==CaptureController.Mode.VIDEO) {
            Size best=null;
            for(Size o:modes) if(o.type==UVCCamera.UVC_VS_FRAME_MJPEG && o.width<=1920 && o.height<=1080) { best=at(o,rate(o,30)); break; } // largest first
            if(best==null || same(best,mode)) return null;
            Size to=best;
            if(big) return new CaptureBar.Hint("Video is 1080p at most; a larger stream makes it choppy.","Switch to "+shortName(to),() -> switchMode(to));
            if(mode.fps<24 && to.fps>mode.fps) return new CaptureBar.Hint("This camera mode runs at "+mode.fps+" fps.","Switch to "+shortName(to),() -> switchMode(to));
            return null;
        }
        if(m!=CaptureController.Mode.TIMELAPSE) return null;
        Size best=null;
        for(Size o:modes) if(o.width<=1920 && o.height<=1080) {
            int low=o.fpsList.get(o.fpsList.size()-1);
            if(best==null || o.width*o.height>best.width*best.height || (o.width*o.height==best.width*best.height && low<best.fps)) best=at(o,low);
        }
        if(best==null || same(best,mode)) return null;
        Size to=best;
        if(big) return new CaptureBar.Hint("Time-lapse is 1080p at most; a smaller, slower stream saves battery.","Switch to "+shortName(to),() -> switchMode(to));
        if(mode.width==to.width && mode.height==to.height && mode.fps>to.fps) return new CaptureBar.Hint("A slower camera stream saves battery on long time-lapses.","Switch to "+to.fps+" fps",() -> switchMode(to));
        return null;
    }
    private void updatePicker() {
        if(capture==null) return; // CaptureBar reports once while it is being built
        boolean free=!capture.isBusy();
        for(Spinner s:new Spinner[]{sizePicker,ratePicker}) if(s!=null) { s.setEnabled(free); s.setAlpha(free?1f:0.45f); }
    }
    private void showRates(Spinner rates) {
        Size mode=session.mode(), res=mode==null?null:session.find(mode.type,mode.width,mode.height); if(res==null) return;
        List<String> labels=new ArrayList<>(); for(int f:res.fpsList) labels.add(f+" fps");
        fill(rates,labels,Math.max(0,res.fpsList.indexOf(mode.fps)));
        rates.setVisibility(res.fpsList.size()>1?View.VISIBLE:View.GONE);
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
    // "Lock exposure / WB / focus": the auto modes a time-lapse turns off, so frames don't drift or flicker.
    private static boolean lockable(Spec s) { return s.flag==CT_AE_MODE_CONTROL || s.flag==PU_WHITE_BALANCE_TEMPERATURE_AUTO_CONTROL || s.flag==CT_FOCUS_AUTO_CONTROL; }
    // Before a time-lapse: each lockable auto mode that is on goes off, and the camera holds what auto had chosen. Nothing is
    // written back: the XC-TECH camera reports color temperature 4 (of its 1..5 presets) while auto white balance runs, and
    // writing that 4 turned the picture orange, while only switching auto off kept it. The camera remembers manual mode, also
    // across reconnects, hence the offer to turn auto back on.
    private void lockAuto() {
        if(session.state()!=State.OPEN || control==null) return;
        StringBuilder log=new StringBuilder();
        for(Row t:rows) if(t.toggle!=null && lockable(t.spec) && t.toggle.isChecked()) {
            Row manual=null; for(Row r:rows) if(r.spec.autoFlag==t.spec.flag) manual=r;
            int value=manual==null?0:manual.spec.get.get(control);
            t.spec.set.set(control,0);
            if(!session.locked.contains(t.spec.flag)) session.locked.add(t.spec.flag);
            log.append(log.length()==0?"":", ").append(t.spec.label).append(" off").append(manual==null?"":" ("+manual.spec.label+" read "+value+")");
        }
        if(!session.locked.isEmpty()) session.lockedCamera=cameraKey(session.selected());
        android.util.Log.i("UsbCameraLab","lock: "+(log.length()==0?"nothing on to lock":log));
        ui.removeCallbacks(refresh); refresh.run();
    }
    // After a time-lapse, or at the next open of that camera when the run ended with it: offer to turn the auto modes back on.
    private void offerRestore() {
        List<Spec> locked=locked();
        if(locked.isEmpty() || restoreDialog!=null || session.state()!=State.OPEN || control==null || capture.isBusy()) return;
        if(!cameraKey(session.selected()).equals(session.lockedCamera)) return;
        StringBuilder names=new StringBuilder();
        for(int i=0;i<locked.size();i++) names.append(i==0?"":i==locked.size()-1?" and ":", ").append(locked.get(i).label.toLowerCase(Locale.US));
        restoreDialog=new AlertDialog.Builder(this).setTitle("Turn automatic settings back on?")
                .setMessage("The time-lapse turned off "+names+" to keep its frames steady.")
                .setPositiveButton("Turn back on",(d,w) -> restoreAuto())
                .setNegativeButton("Keep manual",(d,w) -> session.locked.clear())
                .setCancelable(false).setOnDismissListener(d -> restoreDialog=null).show();
    }
    private void restoreAuto() {
        if(session.state()!=State.OPEN || control==null) return; // the camera went away meanwhile: asked again at its next open
        List<Spec> locked=locked();
        for(Spec s:locked) s.set.set(control,1);
        android.util.Log.i("UsbCameraLab","lock: restored "+locked.size()+" auto mode(s)");
        session.locked.clear(); scheduleRefresh();
    }
    // The session's locked auto modes, in the order they were turned off.
    private List<Spec> locked() {
        List<Spec> specs=new ArrayList<>();
        for(int flag:session.locked) for(Spec s:SPECS) if(s.kind==TOGGLE && s.flag==flag) specs.add(s);
        return specs;
    }
    private void showControls() {
        controls.removeAllViews(); rows.clear(); sizePicker=ratePicker=null; if(session.state()!=State.OPEN) return;
        addModePicker();
        control=session.helper().getUVCControl(); if(control==null) return;
        long started=SystemClock.uptimeMillis();
        List<View> views=new ArrayList<>(); String section=null;
        for(Spec s:SPECS) {
            if(!control.checkSupportFlag(s.flag)) continue;
            Row r=new Row(s);
            if(!r.build()) { android.util.Log.w("UsbCameraLab","control "+s.label+" reported but has no usable range"); continue; }
            if(!s.section.equals(section)) { section=s.section; views.add(heading(section)); }
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
        captureBar.captureChanged(); // the lock option needs the controls
        offerRestore();
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
        if(session.state()!=State.OPEN || control==null) return;
        row.spec.set.set(control,value);
        updateEnabled(); scheduleRefresh();
    }
    private void resetAll() {
        if(session.state()!=State.OPEN || control==null) return;
        pendingWrites.clear();
        for(Row r:rows) if(r.spec.kind==RANGE) r.spec.reset.reset(control);
        for(Row r:rows) if(r.spec.kind!=RANGE) r.spec.reset.reset(control); // auto modes last, so they win over manual values
        scheduleRefresh();
    }
    private final Runnable refresh=() -> {
        if(session.state()!=State.OPEN || control==null) return;
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
        if(session.state()==State.OPEN && control!=null) for(Map.Entry<Row,Integer> e:pendingWrites.entrySet()) e.getKey().spec.set.set(control,e.getValue());
        pendingWrites.clear();
    };
    @Override protected void onStart() { super.onStart(); session.attach(screen,captureBar); }
    // A run going on in the background comes back undimmed.
    @Override protected void onStop() { captureBar.wake(); session.detach(screen); super.onStop(); }
    // A run going on in the background keeps the session OPEN, so nothing cleared this screen's pending updates (showControls, refresh).
    @Override protected void onDestroy() { ui.removeCallbacksAndMessages(null); session.forget(captureBar); captureBar.release(); super.onDestroy(); }
}
