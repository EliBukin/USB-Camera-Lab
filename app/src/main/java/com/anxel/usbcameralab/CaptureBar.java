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
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaActionSound;
import android.net.Uri;
import android.text.SpannableString;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

// The capture controls over the preview: last-capture thumbnail, shutter, the PHOTO / VIDEO / TIME-LAPSE switch, and the output
// size or the time-lapse interval. Portrait: the switch in a strip above a row of thumbnail, shutter and size. Landscape: a column
// along the end edge, where a 16:9 image leaves black bars at the sides in full screen. It also draws the shutter flash (`flash`
// covers the preview box under `bar`), the recording status and camera-mode hint (`info`, at the top of the preview box) and the
// dimmed screen (`dim`, over the whole window), plays the capture sounds, and asks for the microphone at the first recording.
@SuppressLint("SetTextI18n") // English-only UI built in code, as in MainActivity
final class CaptureBar implements CaptureController.Listener {
    // What CaptureBar needs from the camera side of MainActivity.
    interface Host {
        void busyChanged();                        // isBusy() may have changed: the camera-mode picker follows
        Hint hint(CaptureController.Mode mode);    // a camera mode that suits this capture mode better, or null
        boolean canLock();                         // the camera has auto exposure, white balance or focus
        void lockAuto();                           // turns them off at their current values, before a time-lapse
        void runEnded();                           // a video or time-lapse finished: offer to turn them back on
    }
    // "4K video is choppy" + "Switch to 1080p", and what the tap does.
    static final class Hint {
        final String text,action; final Runnable apply;
        Hint(String text,String action,Runnable apply) { this.text=text; this.action=action; this.apply=apply; }
    }
    static final int MIC_REQUEST=2; // MainActivity forwards this permission result to micAnswered
    private static final int RED=0xffe53935, MUTED=0xffc8d2da, ACCENT=0xff7fc4e0;
    private static final float DIM=0.01f; // window brightness while dimmed; the device setting is untouched
    final FrameLayout bar,dim;
    final View flash;
    final LinearLayout info;
    private final Activity activity;
    private final Host host;
    private final SharedPreferences prefs;
    private final ImageView thumb;
    private final TextView dimButton;
    private final View shutter;
    private final Drawable photoLook,recordLook,timelapseLook,stopLook;
    private final LinearLayout switcher,side;
    private final TextView interval,size,timer,soundChip,hint,dimText;
    private final MediaActionSound sound=new MediaActionSound();
    private CaptureController controller;
    private Hint shownHint;
    private Uri last;
    private boolean lastVideo, lock, dimmed;
    private int navigationColor; // the window's, while dimmed
    private String warning; // shown on the dimmed screen

    CaptureBar(Activity activity,Host host) {
        this.activity=activity; this.host=host;
        prefs=activity.getSharedPreferences(CaptureController.PREFS,Context.MODE_PRIVATE);
        lock=prefs.getBoolean("lock",false);
        sound.load(MediaActionSound.SHUTTER_CLICK); sound.load(MediaActionSound.START_VIDEO_RECORDING); sound.load(MediaActionSound.STOP_VIDEO_RECORDING);
        flash=new View(activity); flash.setBackgroundColor(0xffffffff); flash.setAlpha(0f); flash.setVisibility(View.INVISIBLE);
        bar=new FrameLayout(activity);
        thumb=new ImageView(activity); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setVisibility(View.INVISIBLE);
        GradientDrawable frame=new GradientDrawable(); frame.setCornerRadius(dp(8)); frame.setColor(0xff38444f); frame.setStroke(dp(2),0xffffffff);
        thumb.setBackground(frame); thumb.setClipToOutline(true); thumb.setPadding(dp(2),dp(2),dp(2),dp(2));
        thumb.setOnClickListener(v -> open());
        // In the thumbnail's place during a time-lapse: opening the gallery would leave the app and end the run.
        dimButton=label("Dim",13); dimButton.setTypeface(Typeface.DEFAULT_BOLD); dimButton.setVisibility(View.GONE);
        GradientDrawable dimBack=new GradientDrawable(); dimBack.setCornerRadius(dp(8)); dimBack.setColor(0xcc101820); dimBack.setStroke(dp(2),0xffffffff);
        dimButton.setBackground(dimBack); dimButton.setContentDescription("Dim the screen");
        dimButton.setOnClickListener(v -> setDimmed(true));
        shutter=new View(activity);
        photoLook=pressable(ringed(disc(0xffffffff),7),ringed(disc(0xffaab4bd),7));
        recordLook=pressable(ringed(disc(RED),7),ringed(disc(0xffa82a27),7));
        timelapseLook=pressable(dashed(ringed(disc(RED),7)),dashed(ringed(disc(0xffa82a27),7)));
        stopLook=pressable(ringed(square(RED),19),ringed(square(0xffa82a27),19));
        shutter.setOnClickListener(v -> press());
        switcher=new LinearLayout(activity); switcher.setGravity(Gravity.CENTER);
        // One label per mode, the current one bright.
        for(CaptureController.Mode m:CaptureController.Mode.values()) {
            String name=m==CaptureController.Mode.TIMELAPSE?"TIME-LAPSE":m.name();
            TextView t=label(name,13); t.setTypeface(Typeface.DEFAULT_BOLD); t.setLetterSpacing(0.08f); t.setTag(m);
            t.setMinHeight(dp(32)); t.setPadding(dp(7),0,dp(7),0);
            t.setContentDescription(name.toLowerCase(Locale.US)+" mode");
            t.setOnClickListener(v -> { if(controller!=null) controller.setMode(m); });
            switcher.addView(t);
        }
        side=new LinearLayout(activity); side.setOrientation(LinearLayout.VERTICAL); side.setGravity(Gravity.CENTER);
        interval=pill(); interval.setTextSize(12); interval.setOnClickListener(v -> chooseInterval());
        side.addView(interval,new LinearLayout.LayoutParams(-2,-2)); // a vertical LinearLayout would stretch it to the size label
        size=label("",11); size.setTextColor(MUTED);
        LinearLayout.LayoutParams below=new LinearLayout.LayoutParams(-2,-2); below.topMargin=dp(3); side.addView(size,below);
        bar.addView(thumb); bar.addView(dimButton); bar.addView(shutter); bar.addView(switcher); bar.addView(side);
        info=new LinearLayout(activity); info.setOrientation(LinearLayout.VERTICAL); info.setGravity(Gravity.CENTER); info.setVisibility(View.GONE);
        hint=pill(); hint.setMaxWidth(dp(300)); hint.setOnClickListener(v -> { if(shownHint!=null) shownHint.apply.run(); });
        timer=pill(); soundChip=pill();
        info.addView(hint,new LinearLayout.LayoutParams(-2,-2)); info.addView(timer,gap()); info.addView(soundChip,gap());
        dim=new FrameLayout(activity); dim.setBackgroundColor(0xff000000); dim.setVisibility(View.GONE);
        dim.setContentDescription("Screen dimmed. Tap to wake.");
        dim.setOnClickListener(v -> wake());
        dimText=label("",11); dimText.setTextColor(0xff59636b); dimText.setShadowLayer(0,0,0,0);
        dim.addView(dimText,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL));
        captureChanged();
    }
    void attach(CaptureController c) { controller=c; captureChanged(); }
    void release() { sound.release(); }
    void arrange(boolean landscape) {
        int edge=dp(14);
        GradientDrawable scrim=new GradientDrawable(landscape?GradientDrawable.Orientation.RIGHT_LEFT:GradientDrawable.Orientation.BOTTOM_TOP,new int[]{0x99000000,0x00000000});
        bar.setBackground(scrim);
        bar.setLayoutParams(landscape?new FrameLayout.LayoutParams(dp(108),-1,Gravity.END):new FrameLayout.LayoutParams(-1,dp(110),Gravity.BOTTOM));
        switcher.setOrientation(landscape?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams m=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        if(landscape) m.topMargin=dp(10);
        switcher.setLayoutParams(m);
        if(landscape) {
            // Top to bottom: modes, shutter, interval or size (resting on the thumbnail), thumbnail.
            shutter.setLayoutParams(new FrameLayout.LayoutParams(dp(60),dp(60),Gravity.CENTER));
            FrameLayout.LayoutParams s=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL); s.bottomMargin=edge+dp(46+6);
            side.setLayoutParams(s);
            FrameLayout.LayoutParams t=new FrameLayout.LayoutParams(dp(46),dp(46),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL); t.bottomMargin=edge;
            thumb.setLayoutParams(t); dimButton.setLayoutParams(new FrameLayout.LayoutParams(t));
        } else {
            // The modes strip on top, then a 76 dp row: thumbnail, shutter, interval or size.
            FrameLayout.LayoutParams sh=new FrameLayout.LayoutParams(dp(60),dp(60),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL); sh.bottomMargin=dp(8);
            shutter.setLayoutParams(sh);
            FrameLayout.LayoutParams t=new FrameLayout.LayoutParams(dp(46),dp(46),Gravity.BOTTOM|Gravity.START); t.setMarginStart(edge); t.bottomMargin=dp(15);
            thumb.setLayoutParams(t); dimButton.setLayoutParams(new FrameLayout.LayoutParams(t));
            FrameLayout.LayoutParams s=new FrameLayout.LayoutParams(-2,dp(76),Gravity.BOTTOM|Gravity.END); s.setMarginEnd(edge);
            side.setLayoutParams(s);
        }
        // The status and hint centre over the image left of the column, not under it. A centred child moves by its whole end
        // margin, so half the column's width centres it in the rest.
        if(info.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams i=(FrameLayout.LayoutParams)info.getLayoutParams(); i.setMarginEnd(landscape?dp(54):0); info.setLayoutParams(i);
        }
    }

    private void press() {
        if(controller==null) return;
        boolean idle=!controller.recording() && controller.canShoot();
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
        if(results[0]!=PackageManager.PERMISSION_GRANTED) Toast.makeText(activity,"Recording without sound. To record sound, allow the microphone in the app's settings.",Toast.LENGTH_LONG).show();
        if(controller!=null && controller.mode()==CaptureController.Mode.VIDEO && !controller.recording()) controller.shutter();
    }
    // The interval presets, each with what an hour becomes, and the exposure lock.
    private void chooseInterval() {
        if(controller==null || controller.recording()) return;
        PopupMenu menu=new PopupMenu(activity,interval);
        int[] all=TimelapseRecorder.INTERVALS;
        for(int i=0;i<all.length;i++) menu.getMenu().add(1,i,i,"Every "+TimelapseRecorder.every(all[i])+"   ·   1 h → "+perHour(all[i])).setChecked(all[i]==controller.interval());
        menu.getMenu().setGroupCheckable(1,true,true);
        if(host.canLock()) menu.getMenu().add(2,Menu.NONE,all.length,"Lock exposure / WB / focus").setCheckable(true).setChecked(lock);
        menu.setOnMenuItemClickListener((MenuItem item) -> {
            if(item.getGroupId()==1) controller.setInterval(all[item.getItemId()]);
            else { lock=!lock; prefs.edit().putBoolean("lock",lock).apply(); captureChanged(); }
            return true;
        });
        menu.show();
    }
    // Seconds of 30 fps video per hour of time-lapse: "24 s", "2 min", "0.4 s".
    static String perHour(int seconds) {
        double s=3600.0/seconds/TimelapseRecorder.FPS;
        if(s>=60 && s%60==0) return (int)s/60+" min";
        return (s==Math.rint(s)?String.valueOf((int)s):String.format(Locale.US,"%.1f",s))+" s";
    }

    public void captureChanged() {
        boolean on=controller!=null && controller.canShoot(), busy=controller!=null && controller.isBusy();
        CaptureController.Mode mode=controller==null?CaptureController.Mode.PHOTO:controller.mode();
        boolean recording=controller!=null && controller.recording(), timelapse=mode==CaptureController.Mode.TIMELAPSE;
        boolean lapsing=controller!=null && controller.timelapseRunning();
        shutter.setBackground(recording?stopLook:mode==CaptureController.Mode.PHOTO?photoLook:timelapse?timelapseLook:recordLook);
        shutter.setContentDescription(recording?(lapsing?"Stop the time-lapse":"Stop recording"):mode==CaptureController.Mode.PHOTO?"Take a photo":timelapse?"Start the time-lapse":"Start recording");
        shutter.setEnabled(on); shutter.setAlpha(on?1f:0.35f);
        String s=controller==null?null:controller.sizeLabel();
        size.setText(s==null?"":s); size.setVisibility(s==null?View.GONE:View.VISIBLE);
        if(timelapse && controller!=null && !lapsing) {
            int every=controller.interval();
            SpannableString t=new SpannableString("Every "+TimelapseRecorder.every(every)+" ▾\n1 h → "+perHour(every)+(lock && host.canLock()?"\nlocked":""));
            t.setSpan(new ForegroundColorSpan(MUTED),t.toString().indexOf('\n'),t.length(),0);
            interval.setText(t); interval.setContentDescription("Time-lapse interval: every "+TimelapseRecorder.every(every)+". An hour makes "+perHour(every)+" of video."+(lock && host.canLock()?" Exposure, white balance and focus lock at the start.":"")+" Tap to change.");
            interval.setEnabled(!busy); interval.setVisibility(View.VISIBLE);
            size.setVisibility(View.GONE); // no room under the interval; the run shows the size, the hint tells about larger streams
        } else interval.setVisibility(View.GONE);
        for(int i=0;i<switcher.getChildCount();i++) {
            View v=switcher.getChildAt(i); boolean current=v.getTag()==mode;
            v.setEnabled(!busy); v.setAlpha(current?1f:busy?0.25f:0.55f);
        }
        thumb.setVisibility(lapsing || last==null?View.INVISIBLE:View.VISIBLE);
        dimButton.setVisibility(lapsing?View.VISIBLE:View.GONE);
        shownHint=recording || mode==CaptureController.Mode.PHOTO || controller==null || controller.stream()==null?null:host.hint(mode);
        if(shownHint!=null) {
            SpannableString t=new SpannableString(shownHint.text+"\n"+shownHint.action);
            t.setSpan(new ForegroundColorSpan(ACCENT),shownHint.text.length()+1,t.length(),0);
            hint.setText(t); hint.setContentDescription(shownHint.text+" "+shownHint.action+".");
        }
        hint.setVisibility(shownHint==null?View.GONE:View.VISIBLE);
        timer.setVisibility(recording?View.VISIBLE:View.GONE);
        if(!recording) { soundChip.setVisibility(View.GONE); warning=null; }
        info.setVisibility(recording || shownHint!=null?View.VISIBLE:View.GONE);
        if(!lapsing) wake();
        host.busyChanged();
    }
    public void shutterFired() {
        sound.play(MediaActionSound.SHUTTER_CLICK);
        flash.animate().cancel(); flash.setVisibility(View.VISIBLE); flash.setAlpha(0.85f);
        flash.animate().alpha(0f).setDuration(220).withEndAction(() -> flash.setVisibility(View.INVISIBLE)).start();
    }
    public void recordingStarting() {
        sound.play(MediaActionSound.START_VIDEO_RECORDING);
        if(controller!=null && controller.mode()==CaptureController.Mode.TIMELAPSE) timelapseProgress(0,0); else recordingProgress(0,0);
        soundChip.setVisibility(View.GONE);
    }
    public void recordingProgress(long ms,long bytes) {
        SpannableString t=new SpannableString("● "+clock(ms)+" · "+Formatter.formatShortFileSize(activity,bytes));
        t.setSpan(new ForegroundColorSpan(0xffff5a52),0,1,0);
        timer.setText(t);
    }
    // "● Frame 154 · every 5 s / 0:05 of video · 12:50 elapsed"
    public void timelapseProgress(int frames,long ms) {
        if(controller==null) return;
        String every="every "+TimelapseRecorder.every(controller.interval()), video=TimelapseRecorder.length(frames)+" of video", elapsed=clock(ms)+" elapsed";
        SpannableString t=new SpannableString("● Frame "+frames+" · "+every+"\n"+video+" · "+elapsed);
        t.setSpan(new ForegroundColorSpan(0xffff5a52),0,1,0);
        timer.setText(t);
        // Dimmed: one dark line that moves a little every minute, so nothing stays lit in one place for hours.
        dimText.setText("Time-lapse · frame "+frames+" · "+elapsed+(warning==null?"":"\n"+warning)+"\nTap to wake");
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)dimText.getLayoutParams();
        int step=(int)(ms/60_000%8), top=dp(120)+step*dp(48);
        if(p.topMargin!=top) { p.topMargin=top; dimText.setLayoutParams(p); }
    }
    public void captureWarning(String message) {
        warning=message;
        android.util.Log.w("UsbCameraLab",message);
        Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
    }
    public void soundSource(String source) { soundChip.setText("Sound: "+source); soundChip.setVisibility(View.VISIBLE); }
    public void recordingEnded(String note) {
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
        wake();
        if(note!=null) { android.util.Log.i("UsbCameraLab",note); Toast.makeText(activity,note,Toast.LENGTH_LONG).show(); }
        host.runEnded();
    }
    public void lastSaved(Uri uri,boolean video,Bitmap picture) {
        last=uri; lastVideo=video;
        if(controller==null || !controller.timelapseRunning()) thumb.setVisibility(View.VISIBLE);
        thumb.setContentDescription(video?"Open the last video":"Open the last photo");
        if(picture!=null) thumb.setImageBitmap(picture); else thumb.setImageDrawable(null);
    }
    public void captureFailed(String message) {
        android.util.Log.w("UsbCameraLab",message);
        Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
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
        catch(ActivityNotFoundException e) { Toast.makeText(activity,lastVideo?"No app here can play videos":"No app here can show photos",Toast.LENGTH_LONG).show(); }
    }

    private static String clock(long ms) {
        long s=ms/1000;
        return s>=3600?String.format(Locale.US,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.US,"%02d:%02d",s/60,s%60);
    }
    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams gap() { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2); p.topMargin=dp(6); return p; }
    private TextView label(String value,int sp) {
        TextView t=new TextView(activity); t.setText(value); t.setTextColor(0xffffffff); t.setTextSize(sp); t.setGravity(Gravity.CENTER);
        t.setShadowLayer(4,0,1,0xcc000000); return t;
    }
    private TextView pill() {
        TextView t=label("",13); GradientDrawable back=new GradientDrawable(); back.setCornerRadius(dp(14)); back.setColor(0x99000000);
        t.setBackground(back); t.setPadding(dp(10),dp(4),dp(10),dp(4)); t.setVisibility(View.GONE); return t;
    }
    private static Drawable pressable(Drawable normal,Drawable pressed) {
        StateListDrawable d=new StateListDrawable(); d.addState(new int[]{android.R.attr.state_pressed},pressed); d.addState(new int[0],normal); return d;
    }
    private GradientDrawable disc(int fill) { GradientDrawable d=new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(fill); return d; }
    private GradientDrawable square(int fill) { GradientDrawable d=new GradientDrawable(); d.setCornerRadius(dp(4)); d.setColor(fill); return d; }
    // A white ring around `inner`, inset by `gap` dp.
    private Drawable ringed(Drawable inner,int gap) {
        GradientDrawable ring=new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setStroke(dp(3),0xffffffff);
        LayerDrawable d=new LayerDrawable(new Drawable[]{ring,inner}); int g=dp(gap); d.setLayerInset(1,g,g,g,g);
        return d;
    }
    // The time-lapse button: a dashed white ring, like a clock's marks, on the record button.
    private Drawable dashed(Drawable button) {
        GradientDrawable marks=new GradientDrawable(); marks.setShape(GradientDrawable.OVAL); marks.setStroke(dp(2),0xffffffff,dp(3),dp(3));
        LayerDrawable d=new LayerDrawable(new Drawable[]{button,marks}); int g=dp(16); d.setLayerInset(1,g,g,g,g);
        return d;
    }
}
