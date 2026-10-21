package com.anxel.usbcameralab;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.MediaActionSound;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

// The capture controls over the preview: last-photo thumbnail, shutter, and the modes with the photo size. A row along the bottom in
// portrait; a column along the end edge in landscape, where a 16:9 image leaves black bars at the sides in full screen.
// It also draws the shutter flash (`flash` covers the preview box under `bar`) and plays the shutter sound.
final class CaptureBar implements CaptureController.Listener {
    final FrameLayout bar;
    final View flash;
    private final Activity activity;
    private final ImageView thumb;
    private final View shutter;
    private final LinearLayout modes;
    private final TextView size;
    private final MediaActionSound sound=new MediaActionSound();
    private CaptureController controller;
    private Uri last;

    CaptureBar(Activity activity) {
        this.activity=activity;
        sound.load(MediaActionSound.SHUTTER_CLICK);
        flash=new View(activity); flash.setBackgroundColor(0xffffffff); flash.setAlpha(0f); flash.setVisibility(View.INVISIBLE);
        bar=new FrameLayout(activity);
        thumb=new ImageView(activity); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP); thumb.setVisibility(View.INVISIBLE);
        GradientDrawable frame=new GradientDrawable(); frame.setCornerRadius(dp(8)); frame.setColor(0xff38444f); frame.setStroke(dp(2),0xffffffff);
        thumb.setBackground(frame); thumb.setClipToOutline(true); thumb.setPadding(dp(2),dp(2),dp(2),dp(2));
        thumb.setContentDescription("Open the last photo");
        thumb.setOnClickListener(v -> open());
        shutter=new View(activity);
        StateListDrawable look=new StateListDrawable();
        look.addState(new int[]{android.R.attr.state_pressed},circle(0xffaab4bd)); look.addState(new int[0],circle(0xffffffff));
        shutter.setBackground(look); shutter.setContentDescription("Take a photo");
        shutter.setOnClickListener(v -> { if(controller!=null) controller.shutter(); });
        modes=new LinearLayout(activity); modes.setOrientation(LinearLayout.VERTICAL); modes.setGravity(Gravity.CENTER);
        // One label per mode, the current one bright; VIDEO and TIME-LAPSE join the list in later phases.
        for(CaptureController.Mode m:CaptureController.Mode.values()) {
            TextView t=label(m.name(),13); t.setTypeface(Typeface.DEFAULT_BOLD); t.setLetterSpacing(0.08f); t.setTag(m); modes.addView(t);
        }
        size=label("",11); size.setTextColor(0xffc8d2da); modes.addView(size);
        bar.addView(thumb); bar.addView(shutter); bar.addView(modes);
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
    }

    public void captureChanged() {
        boolean on=controller!=null && controller.canShoot();
        shutter.setEnabled(on); shutter.setAlpha(on?1f:0.35f);
        String s=controller==null?null:controller.photoSize();
        size.setText(s==null?"":s); size.setVisibility(s==null?View.GONE:View.VISIBLE);
        for(int i=0;i<modes.getChildCount();i++) {
            View v=modes.getChildAt(i);
            if(v.getTag()!=null) v.setAlpha(controller!=null && v.getTag()==controller.mode()?1f:0.55f);
        }
    }
    public void shutterFired() {
        sound.play(MediaActionSound.SHUTTER_CLICK);
        flash.animate().cancel(); flash.setVisibility(View.VISIBLE); flash.setAlpha(0.85f);
        flash.animate().alpha(0f).setDuration(220).withEndAction(() -> flash.setVisibility(View.INVISIBLE)).start();
    }
    public void lastPhoto(Uri uri,Bitmap picture) {
        last=uri; thumb.setVisibility(View.VISIBLE);
        if(picture!=null) thumb.setImageBitmap(picture); else thumb.setImageDrawable(null);
    }
    public void photoFailed(String message) {
        android.util.Log.w("UsbCameraLab",message);
        Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
    }
    private void open() {
        if(last==null) return;
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(last,"image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch(ActivityNotFoundException e) { Toast.makeText(activity,"No app here can show photos",Toast.LENGTH_LONG).show(); }
    }

    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    private TextView label(String value,int sp) {
        TextView t=new TextView(activity); t.setText(value); t.setTextColor(0xffffffff); t.setTextSize(sp); t.setGravity(Gravity.CENTER);
        t.setShadowLayer(4,0,1,0xcc000000); return t;
    }
    // A white ring around a filled disc.
    private Drawable circle(int fill) {
        GradientDrawable ring=new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setStroke(dp(3),0xffffffff);
        GradientDrawable disc=new GradientDrawable(); disc.setShape(GradientDrawable.OVAL); disc.setColor(fill);
        LayerDrawable d=new LayerDrawable(new Drawable[]{ring,disc}); int gap=dp(7); d.setLayerInset(1,gap,gap,gap,gap);
        return d;
    }
}
