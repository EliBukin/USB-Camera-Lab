package com.anxel.usbcameralab;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import java.util.Locale;

// The capture side of the screen, for the tab being shown (Photo, Video or Time-lapse): the shutter row under the panel in
// portrait (thumbnail or Dim · shutter · Controls), a column at the end edge in landscape (Controls, interval, shutter,
// thumbnail or Dim), the tab panels (CaptureTabs), and on the picture the run's timer, the sound source and, where there is no
// panel, the hint. It also draws the shutter flash and the dimmed screen (`dim`, over the whole window), plays the capture
// sounds, and asks for the microphone at the first recording and, on Android 13+, for notifications at the first video or
// time-lapse. The tab chooses the capture mode; while a run goes on, the other tabs show their shutter off with "Go to …".
@SuppressLint("SetTextI18n") // English-only UI built in code
final class CaptureBar implements CaptureController.Listener, CaptureTabs.Host {
    // What CaptureBar needs from the rest of MainActivity.
    interface Host {
        void busyChanged();                        // isBusy() or recording() may have changed: picker lock, badges, Home
        Hint hint(CaptureController.Mode mode);    // a camera mode that suits this capture mode better, or null
        boolean canLock();                         // the camera has auto exposure, white balance or focus
        void lockAuto();                           // turns them off at their current values, before a time-lapse
        void runEnded();                           // a video or time-lapse finished: offer to turn them back on
        void openControls();                       // the Controls button: the controls sheet
        void goTo(CaptureController.Mode mode);    // "Go to Time-lapse": select that tab
        void message(String text,String action,Runnable onTap); // a snackbar
    }
    // "4K video is choppy" + "Switch to 1080p", and what the tap does.
    static final class Hint {
        final String text,action; final Runnable apply;
        Hint(String text,String action,Runnable apply) { this.text=text; this.action=action; this.apply=apply; }
    }
    static final int MIC_REQUEST=2, NOTIFY_REQUEST=3; // MainActivity forwards these permission results to micAnswered, notifyAnswered
    private static final float DIM=0.01f; // window brightness while dimmed; the device setting is untouched
    final FrameLayout bar,dim;
    final CaptureTabs tabs;
    private final Activity activity;
    private final Host host;
    private final PreviewHost preview;
    private final SharedPreferences prefs;
    private final ImageView thumb;
    private final MaterialButton dimButton, controls;
    private final Chip interval;
    private final View shutter;
    private final Drawable photoLook,recordLook,timelapseLook,stopLook;
    private final TextView dimText;
    private final MediaActionSound sound=new MediaActionSound();
    private CaptureController controller;
    private CaptureController.Mode tab;    // the capture tab shown, or null on Home
    private boolean compact, landscape, fullScreen; // compact: no panel (landscape or full screen), the picture's pills say it
    private Uri last;
    private boolean lastVideo, lock, dimmed, runSaved;
    private int navigationColor; // the window's, while dimmed
    private String warning, soundSource; // the run's: shown on the dimmed screen, the panels and the picture
    private int frames;
    private long runMs, runBytes;

    CaptureBar(Activity activity,PreviewHost preview,Host host) {
        this.activity=activity; this.preview=preview; this.host=host;
        prefs=activity.getSharedPreferences(CaptureController.PREFS,Context.MODE_PRIVATE);
        lock=prefs.getBoolean("lock",false);
        sound.load(MediaActionSound.SHUTTER_CLICK); sound.load(MediaActionSound.START_VIDEO_RECORDING); sound.load(MediaActionSound.STOP_VIDEO_RECORDING);
        tabs=new CaptureTabs(activity,this);
        bar=new FrameLayout(activity);
        thumb=new ImageView(activity); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setVisibility(View.INVISIBLE);
        GradientDrawable frame=new GradientDrawable(); frame.setCornerRadius(dp(14)); frame.setColor(0xff38444f); frame.setStroke(dp(2),0xffffffff);
        thumb.setBackground(frame); thumb.setClipToOutline(true); thumb.setPadding(dp(2),dp(2),dp(2),dp(2));
        thumb.setOnClickListener(v -> open());
        // In the thumbnail's place during a time-lapse.
        dimButton=M3.tonalButton(activity,"Dim",R.drawable.ic_dim); dimButton.setVisibility(View.GONE); dimButton.setContentDescription("Dim the screen");
        dimButton.setOnClickListener(v -> setDimmed(true));
        controls=new MaterialButton(activity,null,R.attr.materialIconButtonFilledTonalStyle); controls.setIconResource(R.drawable.ic_tune);
        controls.setContentDescription("Camera controls"); controls.setOnClickListener(v -> host.openControls());
        interval=new Chip(activity); interval.setOnClickListener(v -> chooseInterval()); interval.setChipIconResource(R.drawable.ic_timer); interval.setChipIconVisible(true);
        interval.setCloseIconResource(R.drawable.ic_expand_more); interval.setCloseIconVisible(true); interval.setOnCloseIconClickListener(v -> chooseInterval());
        shutter=new View(activity);
        photoLook=pressable(ringed(disc(0xffffffff),7),ringed(disc(0xffaab4bd),7));
        recordLook=pressable(ringed(disc(M3.REC),7),ringed(disc(0xffa82a27),7));
        timelapseLook=pressable(dashed(ringed(disc(M3.REC),7)),dashed(ringed(disc(0xffa82a27),7)));
        stopLook=pressable(ringed(square(M3.REC),19),ringed(square(0xffa82a27),19));
        shutter.setOnClickListener(v -> press());
        bar.addView(thumb); bar.addView(dimButton); bar.addView(controls); bar.addView(interval); bar.addView(shutter);
        dim=new FrameLayout(activity); dim.setBackgroundColor(0xff000000); dim.setVisibility(View.GONE);
        dim.setContentDescription("Screen dimmed. Tap to wake.");
        dim.setOnClickListener(v -> wake());
        dimText=new TextView(activity); dimText.setTextColor(0xff59636b); dimText.setTextSize(11); dimText.setGravity(Gravity.CENTER);
        dim.addView(dimText,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL));
        arrange(false,false);
        captureChanged();
    }
    void attach(CaptureController c) { controller=c; captureChanged(); }
    void release() { sound.release(); }
    // The tab shown: null on Home.
    void show(CaptureController.Mode mode) {
        tab=mode;
        tabs.photo.setVisibility(mode==CaptureController.Mode.PHOTO?View.VISIBLE:View.GONE);
        tabs.video.setVisibility(mode==CaptureController.Mode.VIDEO?View.VISIBLE:View.GONE);
        tabs.timelapse.setVisibility(mode==CaptureController.Mode.TIMELAPSE?View.VISIBLE:View.GONE);
        captureChanged();
    }
    // Portrait: a row of thumbnail (or Dim), shutter, Controls, 112 dp tall. Landscape: a column, Controls at the top, the shutter
    // in the middle, the thumbnail (or Dim) at the bottom, and the interval chip above the shutter on the Time-lapse tab.
    void arrange(boolean landscape,boolean fullScreen) {
        this.landscape=landscape; this.fullScreen=fullScreen; compact=landscape || fullScreen;
        int edge=dp(landscape?16:32);
        GradientDrawable scrim=new GradientDrawable(landscape?GradientDrawable.Orientation.RIGHT_LEFT:GradientDrawable.Orientation.BOTTOM_TOP,new int[]{0x99000000,0x00000000});
        bar.setBackground(landscape || fullScreen?scrim:null);
        int shutterSize=dp(landscape?68:76);
        shutter.setLayoutParams(new FrameLayout.LayoutParams(shutterSize,shutterSize,Gravity.CENTER));
        FrameLayout.LayoutParams t=new FrameLayout.LayoutParams(dp(52),dp(52),landscape?Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL:Gravity.CENTER_VERTICAL|Gravity.START);
        FrameLayout.LayoutParams d=new FrameLayout.LayoutParams(-2,dp(48),landscape?Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL:Gravity.CENTER_VERTICAL|Gravity.START);
        FrameLayout.LayoutParams c=new FrameLayout.LayoutParams(dp(52),dp(52),landscape?Gravity.TOP|Gravity.CENTER_HORIZONTAL:Gravity.CENTER_VERTICAL|Gravity.END);
        FrameLayout.LayoutParams i=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER);
        if(landscape) { t.bottomMargin=edge; d.bottomMargin=edge; c.topMargin=edge; i.bottomMargin=shutterSize+dp(16); }
        else { t.setMarginStart(edge); d.setMarginStart(edge-dp(8)); c.setMarginEnd(edge); }
        thumb.setLayoutParams(t); dimButton.setLayoutParams(d); controls.setLayoutParams(c); interval.setLayoutParams(i);
        controls.setInsetTop(0); controls.setInsetBottom(0); controls.setIconSize(dp(24));
        captureChanged();
    }

    private void press() {
        if(controller==null) return;
        boolean idle=!controller.recording() && controller.canShoot();
        shutter.performHapticFeedback(Build.VERSION.SDK_INT>=30?HapticFeedbackConstants.CONFIRM:HapticFeedbackConstants.VIRTUAL_KEY);
        if(controller.mode()!=CaptureController.Mode.PHOTO && idle && askNotifications()) return;
        if(controller.mode()==CaptureController.Mode.VIDEO && idle && askMic()) return;
        if(controller.mode()==CaptureController.Mode.TIMELAPSE && idle && lock && host.canLock()) host.lockAuto();
        controller.shutter();
    }
    // Asks once, at the first recording; that recording starts when the answer arrives, silent if denied.
    private boolean askMic() {
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) return false;
        if(prefs.getBoolean("micAsked",false)) return false;
        prefs.edit().putBoolean("micAsked",true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST);
        return true;
    }
    void micAnswered(int[] results) {
        if(results.length==0) { prefs.edit().putBoolean("micAsked",false).apply(); return; } // interrupted: ask next time
        if(results[0]!=PackageManager.PERMISSION_GRANTED) host.message("Recording without sound. To record sound, allow the microphone in the app's settings.",null,null);
        if(controller!=null && controller.mode()==CaptureController.Mode.VIDEO && !controller.recording()) controller.shutter();
    }
    // Android 13+: the notification that shows a video or time-lapse going on while the app is away needs a permission. Asked once,
    // at the first one, which starts with the answer either way: without it the run still goes on in the background, unseen.
    private boolean askNotifications() {
        if(Build.VERSION.SDK_INT<33 || activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) return false;
        if(prefs.getBoolean("notifyAsked",false)) return false;
        prefs.edit().putBoolean("notifyAsked",true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFY_REQUEST);
        return true;
    }
    void notifyAnswered(int[] results) {
        if(results.length==0) { prefs.edit().putBoolean("notifyAsked",false).apply(); return; } // interrupted: ask next time
        if(controller!=null && !controller.recording()) press(); // the microphone may be asked next
    }
    // Landscape has no panel: the interval chip in the column opens the presets, each with what an hour becomes, and the lock.
    private void chooseInterval() {
        if(controller==null || controller.recording()) return;
        PopupMenu menu=new PopupMenu(activity,interval);
        int[] all=TimelapseRecorder.INTERVALS;
        for(int i=0;i<all.length;i++) menu.getMenu().add(1,i,i,"Every "+TimelapseRecorder.every(all[i])+"   ·   1 h → "+perHour(all[i])).setChecked(all[i]==controller.interval());
        menu.getMenu().setGroupCheckable(1,true,true);
        if(host.canLock()) menu.getMenu().add(2,Menu.NONE,all.length,"Lock exposure / WB / focus").setCheckable(true).setChecked(lock);
        menu.setOnMenuItemClickListener((MenuItem item) -> {
            if(item.getGroupId()==1) controller.setInterval(all[item.getItemId()]);
            else lockChanged(!lock);
            return true;
        });
        menu.show();
    }
    @Override public void intervalPicked(int seconds) { if(controller!=null) controller.setInterval(seconds); }
    @Override public void lockChanged(boolean on) { lock=on; prefs.edit().putBoolean("lock",lock).apply(); captureChanged(); }
    // Seconds of 30 fps video per hour of time-lapse: "24 s", "2 min", "0.4 s".
    static String perHour(int seconds) {
        double s=3600.0/seconds/TimelapseRecorder.FPS;
        if(s>=60 && s%60==0) return (int)s/60+" min";
        return (s==Math.rint(s)?String.valueOf((int)s):String.format(Locale.US,"%.1f",s))+" s";
    }
    private static String name(CaptureController.Mode m) { return m==CaptureController.Mode.TIMELAPSE?"Time-lapse":m==CaptureController.Mode.VIDEO?"Video":"Photo"; }

    public void captureChanged() {
        boolean on=controller!=null && controller.canShoot(), busy=controller!=null && controller.isBusy();
        CaptureController.Mode mode=controller==null?CaptureController.Mode.PHOTO:controller.mode();
        boolean recording=controller!=null && controller.recording(), lapsing=controller!=null && controller.timelapseRunning();
        CaptureController.Mode shown=tab==null?mode:tab;
        boolean ours=shown==mode; // this tab's capture is the controller's: while another one runs, the shutter is off
        boolean timelapse=shown==CaptureController.Mode.TIMELAPSE;
        shutter.setBackground(ours && recording?stopLook:shown==CaptureController.Mode.PHOTO?photoLook:timelapse?timelapseLook:recordLook);
        shutter.setContentDescription(ours && recording?(lapsing?"Stop the time-lapse":"Stop recording"):shown==CaptureController.Mode.PHOTO?"Take a photo":timelapse?"Start the time-lapse":"Start recording");
        boolean enabled=on && ours;
        shutter.setEnabled(enabled); shutter.setAlpha(enabled?1f:0.35f);
        boolean dimHere=lapsing && timelapse;
        thumb.setVisibility(dimHere || last==null?View.INVISIBLE:View.VISIBLE);
        dimButton.setVisibility(dimHere?View.VISIBLE:View.GONE);
        controls.setVisibility(fullScreen?View.GONE:View.VISIBLE);
        if(controller!=null) {
            int every=controller.interval();
            interval.setText(TimelapseRecorder.every(every));
            interval.setContentDescription("Time-lapse interval: every "+TimelapseRecorder.every(every)+". An hour makes "+perHour(every)+" of video."+(lock && host.canLock()?" Exposure, white balance and focus lock at the start.":"")+" Tap to change.");
        }
        interval.setVisibility(landscape && !fullScreen && timelapse && !lapsing?View.VISIBLE:View.GONE);
        interval.setEnabled(!busy);
        // The panels.
        String size=controller==null?null:controller.sizeLabel();
        tabs.photo(controller==null || controller.stream()==null?null:controller.stream().width+" × "+controller.stream().height);
        tabs.output(size);
        if(!recording || mode!=CaptureController.Mode.VIDEO) soundIdle();
        tabs.timelapse(lapsing,controller==null?5:controller.interval(),size,host.canLock(),lock,busy);
        Hint hint=recording || controller==null || controller.stream()==null?null:host.hint(shown);
        Hint videoHint=hint!=null && shown==CaptureController.Mode.VIDEO?hint:null, lapseHint=hint!=null && timelapse?hint:null;
        if(videoHint!=null) tabs.videoHint.show(activity,1,R.drawable.ic_info,videoHint.text,videoHint.action,videoHint.apply); else tabs.videoHint.hide();
        if(lapseHint!=null) tabs.lapseHint.show(activity,2,R.drawable.ic_battery_alert,lapseHint.text,lapseHint.action,lapseHint.apply); else tabs.lapseHint.hide();
        String goTo=recording && !ours?(lapsing?"A time-lapse is running.":"A video is recording."):null;
        for(M3.Banner b:new M3.Banner[]{tabs.photoGoTo,tabs.videoGoTo,tabs.lapseGoTo}) {
            if(goTo==null) b.hide(); else b.show(activity,0,R.drawable.ic_info,goTo+" This tab's shutter is off until it stops.","Go to "+name(mode),() -> host.goTo(mode));
        }
        if(lapsing && warning!=null) tabs.lapseWarning.show(activity,2,R.drawable.ic_warning,warning,null,null); else tabs.lapseWarning.hide();
        // The picture: the run's pill, its sound source, and where there is no panel, the hint or "Go to …".
        preview.setRunning(recording);
        renderRun();
        boolean pills=tab!=null && compact;
        if(pills && goTo!=null) hintPill(goTo,"Go to "+name(mode),() -> host.goTo(mode));
        else if(pills && hint!=null) hintPill(hint.text,hint.action,hint.apply);
        else preview.hint.setVisibility(View.GONE);
        if(!recording) { warning=null; soundSource=null; }
        if(!lapsing) wake();
        host.busyChanged();
    }
    private void hintPill(String text,String action,Runnable apply) {
        android.text.SpannableString t=new android.text.SpannableString(text+"\n"+action);
        t.setSpan(new android.text.style.ForegroundColorSpan(M3.color(activity,R.attr.colorPrimary)),text.length()+1,t.length(),0);
        preview.hint.setText(t); preview.hint.setContentDescription(text+" "+action+".");
        preview.hint.setOnClickListener(v -> apply.run());
        preview.hint.setVisibility(View.VISIBLE);
    }
    // Before a recording: what the sound will be.
    private void soundIdle() {
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) tabs.sound("Sound: camera mic or phone mic","The camera's own mic when Android lists it, else the phone's");
        else if(prefs.getBoolean("micAsked",false)) tabs.sound("Sound: off","The microphone isn't allowed; allow it in the app's settings to record sound");
        else tabs.sound("Sound: on","Asks for the microphone at the first recording");
    }
    // The run's pill on the picture. Its own tab: "● 01:23 · 145 MB", "● Frame 154 · every 5 s" (with the video so far and the
    // elapsed time where there is no panel). Other tabs and Home: "● Video · 01:23", "● Time-lapse · frame 154".
    private void renderRun() {
        if(controller==null || !controller.recording()) { preview.run.setVisibility(View.GONE); preview.sound.setVisibility(View.GONE); return; }
        boolean lapsing=controller.timelapseRunning(), own=tab==controller.mode();
        String text;
        if(lapsing) text=own?"● Frame "+frames+" · every "+TimelapseRecorder.every(controller.interval())+(compact?"\n"+TimelapseRecorder.length(frames)+" of video · "+clock(runMs)+" elapsed":""):"● Time-lapse · frame "+frames;
        else text=own?"● "+clock(runMs)+" · "+Formatter.formatShortFileSize(activity,runBytes):"● Video · "+clock(runMs);
        preview.run.setText(text); preview.run.setVisibility(View.VISIBLE);
        boolean showSound=!lapsing && own && compact && soundSource!=null;
        preview.sound.setText(soundSource==null?"":soundSource); preview.sound.setVisibility(showSound?View.VISIBLE:View.GONE);
    }
    public void shutterFired() {
        sound.play(MediaActionSound.SHUTTER_CLICK);
        preview.showFlash();
    }
    public void recordingStarting() {
        sound.play(MediaActionSound.START_VIDEO_RECORDING);
        runSaved=false; soundSource=null; warning=null;
        if(controller!=null && controller.mode()==CaptureController.Mode.TIMELAPSE) timelapseProgress(0,0); else recordingProgress(0,0);
    }
    public void recordingProgress(long ms,long bytes) { runMs=ms; runBytes=bytes; renderRun(); }
    // "Time-lapse · frame 154 · 12:50 elapsed" on the dimmed screen too.
    public void timelapseProgress(int n,long ms) {
        if(controller==null) return;
        frames=n; runMs=ms;
        renderRun(); tabs.timelapseProgress(n,ms);
        // Dimmed: one dark line that moves a little every minute, so nothing stays lit in one place for hours.
        dimText.setText("Time-lapse · frame "+n+" · "+clock(ms)+" elapsed"+(warning==null?"":"\n"+warning)+"\nTap to wake");
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)dimText.getLayoutParams();
        int step=(int)(ms/60_000%8), top=dp(120)+step*dp(48);
        if(p.topMargin!=top) { p.topMargin=top; dimText.setLayoutParams(p); }
    }
    public void captureWarning(String message) {
        warning=message;
        android.util.Log.w("UsbCameraLab",message);
        host.message(message,null,null);
        captureChanged();
    }
    // "phone mic (camera mic is silent)": "Sound: phone mic" over "Camera mic is silent".
    public void soundSource(String source) {
        soundSource=source;
        int open=source.indexOf(" (");
        String what=open<0?source:source.substring(0,open), why=open<0?null:source.substring(open+2,source.length()-1);
        tabs.sound("Sound: "+what,why==null?null:Character.toUpperCase(why.charAt(0))+why.substring(1));
        renderRun();
    }
    public void recordingEnded(String note) {
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
        wake();
        boolean lapse=controller!=null && controller.mode()==CaptureController.Mode.TIMELAPSE;
        if(note!=null) android.util.Log.i("UsbCameraLab",note);
        String text=note!=null?note:lapse?"Time-lapse saved":"Video saved";
        host.message(text,runSaved && last!=null?"View":null,this::open);
        host.runEnded();
    }
    public void lastSaved(Uri uri,boolean video,Bitmap picture) {
        last=uri; lastVideo=video; if(video) runSaved=true;
        thumb.setContentDescription(video?"Open the last video":"Open the last photo");
        if(picture!=null) thumb.setImageBitmap(picture); else thumb.setImageDrawable(null);
        captureChanged();
    }
    public void captureFailed(String message) {
        android.util.Log.w("UsbCameraLab",message);
        host.message(message,null,null);
    }
    // Dimmed: the window at its lowest brightness under a black cover, the navigation bar black too. Only this window changes;
    // the device setting stays.
    @SuppressWarnings("deprecation") // setNavigationBarColor: Android 15 draws apps behind the bars instead (targetSdk 34 here)
    private void setDimmed(boolean on) {
        if(on==dimmed) return;
        dimmed=on;
        WindowManager.LayoutParams w=activity.getWindow().getAttributes();
        w.screenBrightness=on?DIM:WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        activity.getWindow().setAttributes(w);
        if(on) navigationColor=activity.getWindow().getNavigationBarColor();
        activity.getWindow().setNavigationBarColor(on?0xff000000:navigationColor);
        dim.setVisibility(on?View.VISIBLE:View.GONE);
        android.util.Log.i("UsbCameraLab",on?"screen dimmed":"screen awake");
    }
    // Undims; true when the screen was dimmed (Back wakes it instead of leaving).
    boolean wake() { boolean was=dimmed; setDimmed(false); return was; }
    private void open() {
        if(last==null) return;
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(last,lastVideo?"video/mp4":"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch(ActivityNotFoundException e) { host.message(lastVideo?"No app here can play videos":"No app here can show photos",null,null); }
    }

    static String clock(long ms) {
        long s=ms/1000;
        return s>=3600?String.format(Locale.US,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.US,"%02d:%02d",s/60,s%60);
    }
    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    private static Drawable pressable(Drawable normal,Drawable pressed) {
        StateListDrawable d=new StateListDrawable(); d.addState(new int[]{android.R.attr.state_pressed},pressed); d.addState(new int[0],normal); return d;
    }
    private GradientDrawable disc(int fill) { GradientDrawable d=new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(fill); return d; }
    private GradientDrawable square(int fill) { GradientDrawable d=new GradientDrawable(); d.setCornerRadius(dp(4)); d.setColor(fill); return d; }
    // A white ring around `inner`, inset by `gap` dp.
    private Drawable ringed(Drawable inner,int gap) {
        GradientDrawable ring=new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setStroke(dp(4),0xffffffff);
        LayerDrawable d=new LayerDrawable(new Drawable[]{ring,inner}); int g=dp(gap); d.setLayerInset(1,g,g,g,g);
        return d;
    }
    // The time-lapse button: a dashed white ring, like a clock's marks, on the record button.
    private Drawable dashed(Drawable button) {
        GradientDrawable marks=new GradientDrawable(); marks.setShape(GradientDrawable.OVAL); marks.setStroke(dp(2),0xffffffff,dp(3),dp(3));
        LayerDrawable d=new LayerDrawable(new Drawable[]{button,marks}); int g=dp(18); d.setLayerInset(1,g,g,g,g);
        return d;
    }
}
