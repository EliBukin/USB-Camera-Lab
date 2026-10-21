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
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

// The capture controls over the preview: last-capture thumbnail, shutter, and the PHOTO / VIDEO switch with the output size. A
// row along the bottom in portrait; a column along the end edge in landscape, where a 16:9 image leaves black bars at the sides
// in full screen. It also draws the shutter flash (`flash` covers the preview box under `bar`), the recording time and sound
// source (`info`, at the top of the preview box), plays the capture sounds, and asks for the microphone at the first recording.
@SuppressLint("SetTextI18n") // English-only UI built in code, as in MainActivity
final class CaptureBar implements CaptureController.Listener {
    static final int MIC_REQUEST=2; // MainActivity forwards this permission result to micAnswered
    private static final int RED=0xffe53935;
    final FrameLayout bar;
    final View flash;
    final LinearLayout info;
    private final Activity activity;
    private final Runnable changed; // tells MainActivity when isBusy() may have changed, for the camera-mode picker
    private final ImageView thumb;
    private final View shutter;
    private final Drawable photoLook,recordLook,stopLook;
    private final LinearLayout modes,switcher;
    private final TextView size,timer,soundChip;
    private final MediaActionSound sound=new MediaActionSound();
    private CaptureController controller;
    private Uri last;
    private boolean lastVideo;

    CaptureBar(Activity activity,Runnable changed) {
        this.activity=activity; this.changed=changed;
        sound.load(MediaActionSound.SHUTTER_CLICK); sound.load(MediaActionSound.START_VIDEO_RECORDING); sound.load(MediaActionSound.STOP_VIDEO_RECORDING);
        flash=new View(activity); flash.setBackgroundColor(0xffffffff); flash.setAlpha(0f); flash.setVisibility(View.INVISIBLE);
        bar=new FrameLayout(activity);
        thumb=new ImageView(activity); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setVisibility(View.INVISIBLE);
        GradientDrawable frame=new GradientDrawable(); frame.setCornerRadius(dp(8)); frame.setColor(0xff38444f); frame.setStroke(dp(2),0xffffffff);
        thumb.setBackground(frame); thumb.setClipToOutline(true); thumb.setPadding(dp(2),dp(2),dp(2),dp(2));
        thumb.setOnClickListener(v -> open());
        shutter=new View(activity);
        photoLook=pressable(ringed(disc(0xffffffff),7),ringed(disc(0xffaab4bd),7));
        recordLook=pressable(ringed(disc(RED),7),ringed(disc(0xffa82a27),7));
        stopLook=pressable(ringed(square(RED),19),ringed(square(0xffa82a27),19));
        shutter.setOnClickListener(v -> press());
        modes=new LinearLayout(activity); modes.setOrientation(LinearLayout.VERTICAL); modes.setGravity(Gravity.CENTER);
        switcher=new LinearLayout(activity); switcher.setGravity(Gravity.CENTER); modes.addView(switcher);
        // One label per mode, the current one bright; TIME-LAPSE joins the list in a later phase.
        for(CaptureController.Mode m:CaptureController.Mode.values()) {
            TextView t=label(m.name(),13); t.setTypeface(Typeface.DEFAULT_BOLD); t.setLetterSpacing(0.08f); t.setTag(m);
            t.setMinHeight(dp(36)); t.setPadding(dp(7),0,dp(7),0);
            t.setContentDescription(m.name().toLowerCase(Locale.US)+" mode");
            t.setOnClickListener(v -> { if(controller!=null) controller.setMode(m); });
            switcher.addView(t);
        }
        size=label("",11); size.setTextColor(0xffc8d2da); modes.addView(size);
        bar.addView(thumb); bar.addView(shutter); bar.addView(modes);
        info=new LinearLayout(activity); info.setOrientation(LinearLayout.VERTICAL); info.setGravity(Gravity.CENTER); info.setVisibility(View.GONE);
        timer=pill(); soundChip=pill(); soundChip.setVisibility(View.GONE);
        info.addView(timer); LinearLayout.LayoutParams gap=new LinearLayout.LayoutParams(-2,-2); gap.topMargin=dp(6); info.addView(soundChip,gap);
        captureChanged();
    }
    void attach(CaptureController c) { controller=c; captureChanged(); }
    void release() { sound.release(); }
    void arrange(boolean landscape) {
        int edge=dp(14);
        GradientDrawable scrim=new GradientDrawable(landscape?GradientDrawable.Orientation.RIGHT_LEFT:GradientDrawable.Orientation.BOTTOM_TOP,new int[]{0x99000000,0x00000000});
        bar.setBackground(scrim);
        bar.setLayoutParams(landscape?new FrameLayout.LayoutParams(dp(92),-1,Gravity.END):new FrameLayout.LayoutParams(-1,dp(76),Gravity.BOTTOM));
        shutter.setLayoutParams(new FrameLayout.LayoutParams(dp(60),dp(60),Gravity.CENTER));
        FrameLayout.LayoutParams t=new FrameLayout.LayoutParams(dp(46),dp(46),landscape?Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL:Gravity.START|Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams m=new FrameLayout.LayoutParams(-2,-2,landscape?Gravity.TOP|Gravity.CENTER_HORIZONTAL:Gravity.END|Gravity.CENTER_VERTICAL);
        if(landscape) { t.bottomMargin=edge; m.topMargin=edge; } else { t.setMarginStart(edge); m.setMarginEnd(edge); }
        thumb.setLayoutParams(t); modes.setLayoutParams(m);
        switcher.setOrientation(landscape?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
    }

    private void press() {
        if(controller==null) return;
        if(controller.mode()==CaptureController.Mode.VIDEO && !controller.recording() && controller.canShoot() && askMic()) return;
        controller.shutter();
    }
    // Asks once, at the first recording; that recording starts when the answer arrives, silent if denied.
    private boolean askMic() {
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) return false;
        SharedPreferences p=activity.getSharedPreferences("capture",Context.MODE_PRIVATE);
        if(p.getBoolean("micAsked",false)) return false;
        p.edit().putBoolean("micAsked",true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST);
        return true;
    }
    void micAnswered(int[] results) {
        if(results.length==0) { activity.getSharedPreferences("capture",Context.MODE_PRIVATE).edit().putBoolean("micAsked",false).apply(); return; } // interrupted: ask next time
        if(results[0]!=PackageManager.PERMISSION_GRANTED) Toast.makeText(activity,"Recording without sound. To record sound, allow the microphone in the app's settings.",Toast.LENGTH_LONG).show();
        if(controller!=null && controller.mode()==CaptureController.Mode.VIDEO && !controller.recording()) controller.shutter();
    }

    public void captureChanged() {
        boolean on=controller!=null && controller.canShoot(), busy=controller!=null && controller.isBusy();
        boolean video=controller!=null && controller.mode()==CaptureController.Mode.VIDEO, recording=controller!=null && controller.recording();
        shutter.setBackground(!video?photoLook:recording?stopLook:recordLook);
        shutter.setContentDescription(!video?"Take a photo":recording?"Stop recording":"Start recording");
        shutter.setEnabled(on); shutter.setAlpha(on?1f:0.35f);
        String s=controller==null?null:controller.sizeLabel();
        size.setText(s==null?"":s); size.setVisibility(s==null?View.GONE:View.VISIBLE);
        for(int i=0;i<switcher.getChildCount();i++) {
            View v=switcher.getChildAt(i); boolean current=controller!=null && v.getTag()==controller.mode();
            v.setEnabled(!busy); v.setAlpha(current?1f:busy?0.25f:0.55f);
        }
        if(!recording) info.setVisibility(View.GONE);
        changed.run();
    }
    public void shutterFired() {
        sound.play(MediaActionSound.SHUTTER_CLICK);
        flash.animate().cancel(); flash.setVisibility(View.VISIBLE); flash.setAlpha(0.85f);
        flash.animate().alpha(0f).setDuration(220).withEndAction(() -> flash.setVisibility(View.INVISIBLE)).start();
    }
    public void recordingStarting() {
        sound.play(MediaActionSound.START_VIDEO_RECORDING);
        recordingProgress(0,0); soundChip.setVisibility(View.GONE); info.setVisibility(View.VISIBLE);
    }
    public void recordingProgress(long ms,long bytes) {
        long s=ms/1000;
        String clock=s>=3600?String.format(Locale.US,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.US,"%02d:%02d",s/60,s%60);
        SpannableString t=new SpannableString("● "+clock+" · "+Formatter.formatShortFileSize(activity,bytes));
        t.setSpan(new ForegroundColorSpan(0xffff5a52),0,1,0);
        timer.setText(t);
    }
    public void soundSource(String source) { soundChip.setText("Sound: "+source); soundChip.setVisibility(View.VISIBLE); }
    public void recordingEnded(String note) {
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
        info.setVisibility(View.GONE);
        if(note!=null) { android.util.Log.i("UsbCameraLab",note); Toast.makeText(activity,note,Toast.LENGTH_LONG).show(); }
    }
    public void lastSaved(Uri uri,boolean video,Bitmap picture) {
        last=uri; lastVideo=video; thumb.setVisibility(View.VISIBLE);
        thumb.setContentDescription(video?"Open the last video":"Open the last photo");
        if(picture!=null) thumb.setImageBitmap(picture); else thumb.setImageDrawable(null);
    }
    public void captureFailed(String message) {
        android.util.Log.w("UsbCameraLab",message);
        Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
    }
    private void open() {
        if(last==null) return;
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(last,lastVideo?"video/mp4":"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch(ActivityNotFoundException e) { Toast.makeText(activity,lastVideo?"No app here can play videos":"No app here can show photos",Toast.LENGTH_LONG).show(); }
    }

    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    private TextView label(String value,int sp) {
        TextView t=new TextView(activity); t.setText(value); t.setTextColor(0xffffffff); t.setTextSize(sp); t.setGravity(Gravity.CENTER);
        t.setShadowLayer(4,0,1,0xcc000000); return t;
    }
    private TextView pill() {
        TextView t=label("",13); GradientDrawable back=new GradientDrawable(); back.setCornerRadius(dp(14)); back.setColor(0x99000000);
        t.setBackground(back); t.setPadding(dp(10),dp(4),dp(10),dp(4)); return t;
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
}
