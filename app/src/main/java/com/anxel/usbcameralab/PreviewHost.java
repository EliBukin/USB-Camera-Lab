package com.anxel.usbcameralab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

// The live picture: one SurfaceView, letterboxed on black at the camera's aspect ratio, that is never re-parented or hidden
// (either destroys its surface: the stream goes on but the picture blinks). Tabs, sheets, rotation and full screen only change
// this box's size and what sits on the picture: the camera-mode chip, the run's timer, the sound source, a hint, the full-screen
// button, the shutter flash, and a placeholder with the status while there is no picture.
@SuppressLint({"SetTextI18n","ViewConstructor"})
final class PreviewHost {
    interface Host {
        void previewTapped();   // full screen on or off
        void chipTapped();      // the camera-mode chip: the mode sheet
    }
    final Box box;
    final SurfaceView surface;
    final FrameLayout hud;       // over the picture (less what the capture column covers)
    final View flash;
    final TextView chip, run, sound, hint;
    final MaterialButton full;
    private final LinearLayout info, placeholder;
    private final TextView status;
    private final MaterialButton action;
    private final Context context;
    private boolean running;

    PreviewHost(Context context,Host host) {
        this.context=context;
        box=new Box(context); box.setBackgroundColor(0xff000000);
        box.setOnClickListener(v -> host.previewTapped());
        box.setContentDescription("Camera preview. Tap to enter or leave full screen.");
        surface=new SurfaceView(context); box.addView(surface);
        flash=new View(context); flash.setBackgroundColor(0xffffffff); flash.setAlpha(0f); flash.setVisibility(View.INVISIBLE); box.addView(flash);
        hud=new FrameLayout(context); box.addView(hud);
        chip=M3.pill(context,M3.PILL); chip.setOnClickListener(v -> host.chipTapped());
        // A 32 dp pill in a 48 dp touch target: the backing is inset 8 dp above and below.
        chip.setBackground(new InsetDrawable(chip.getBackground(),0,M3.dp(context,8),0,M3.dp(context,8))); chip.setMinHeight(M3.dp(context,48));
        chip.setPadding(M3.dp(context,12),M3.dp(context,12),M3.dp(context,12),M3.dp(context,12));
        hud.addView(chip,new FrameLayout.LayoutParams(-2,-2));
        info=M3.column(context); info.setGravity(Gravity.CENTER_HORIZONTAL);
        hint=M3.pill(context,M3.PILL); hint.setGravity(Gravity.CENTER); hint.setMaxWidth(M3.dp(context,300)); hint.setVisibility(View.GONE);
        run=M3.pill(context,M3.PILL_RED); run.setGravity(Gravity.CENTER); run.setVisibility(View.GONE);
        sound=M3.pill(context,M3.PILL); sound.setVisibility(View.GONE);
        sound.setCompoundDrawablesRelativeWithIntrinsicBounds(small(R.drawable.ic_mic),null,null,null);
        info.addView(hint,new LinearLayout.LayoutParams(-2,-2)); info.addView(run,M3.margins(context,-2,-2,0,6,0,0)); info.addView(sound,M3.margins(context,-2,-2,0,6,0,0));
        hud.addView(info,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        full=new MaterialButton(context,null,R.attr.materialIconButtonStyle); full.setIconResource(R.drawable.ic_fullscreen); full.setIconTint(M3.tint(0xffffffff));
        full.setBackgroundTintList(M3.tint(0x73000000)); full.setInsetTop(0); full.setInsetBottom(0); full.setPadding(M3.dp(context,12),M3.dp(context,12),M3.dp(context,12),M3.dp(context,12));
        full.setOnClickListener(v -> host.previewTapped());
        hud.addView(full,new FrameLayout.LayoutParams(M3.dp(context,48),M3.dp(context,48)));
        placeholder=M3.column(context); placeholder.setGravity(Gravity.CENTER); placeholder.setBackgroundColor(M3.color(context,R.attr.colorSurfaceContainer));
        placeholder.setPadding(M3.dp(context,24),M3.dp(context,8),M3.dp(context,24),M3.dp(context,8));
        placeholder.addView(M3.image(context,R.drawable.ic_usb,M3.color(context,R.attr.colorOnSurfaceVariant),40));
        placeholder.addView(M3.small(context,"No picture"),M3.margins(context,-2,-2,0,6,0,0));
        status=M3.body(context,""); status.setGravity(Gravity.CENTER); status.setMaxWidth(M3.dp(context,320));
        placeholder.addView(status,M3.margins(context,-2,-2,0,8,0,0));
        action=M3.filledButton(context,"",R.drawable.ic_refresh); action.setVisibility(View.GONE);
        placeholder.addView(action,M3.margins(context,-2,-2,0,8,0,0));
        placeholder.setClickable(true); // taps on it don't reach the box (no full screen without a picture)
        box.addView(placeholder);
        box.picture=surface; box.flash=flash; box.hud=hud; box.placeholder=placeholder;
        setRunning(false);
    }
    private android.graphics.drawable.Drawable small(int res) {
        android.graphics.drawable.Drawable d=M3.icon(context,res,0xffffffff); if(d!=null) d.setBounds(0,0,M3.dp(context,16),M3.dp(context,16));
        return d;
    }

    void setAspect(double aspect) { if(aspect>0 && aspect!=box.aspect) { box.aspect=aspect; box.requestLayout(); } }
    // Landscape: the capture column stands at the box's end edge; the picture keeps clear of it when there is room, and the
    // chips always do.
    void setReserve(int endPx) { if(endPx!=box.reserve) { box.reserve=endPx; box.requestLayout(); } }
    // Idle: the chip at the top start and full screen at the top end. During a run the timer takes the top centre, and the
    // locked chip and full screen move to the bottom.
    void setRunning(boolean on) {
        running=on;
        int edge=M3.dp(context,8);
        FrameLayout.LayoutParams c=new FrameLayout.LayoutParams(-2,-2,(on?Gravity.BOTTOM:Gravity.TOP)|Gravity.START); c.setMargins(edge+M3.dp(context,2),0,edge,0);
        chip.setLayoutParams(c);
        FrameLayout.LayoutParams f=new FrameLayout.LayoutParams(M3.dp(context,48),M3.dp(context,48),(on?Gravity.BOTTOM:Gravity.TOP)|Gravity.END); f.setMargins(edge,0,0,0);
        full.setLayoutParams(f);
        FrameLayout.LayoutParams i=(FrameLayout.LayoutParams)info.getLayoutParams(); i.topMargin=on || chip.getVisibility()!=View.VISIBLE?edge:M3.dp(context,50); info.setLayoutParams(i);
    }
    void setFullScreen(boolean on) {
        full.setIconResource(on?R.drawable.ic_fullscreen_exit:R.drawable.ic_fullscreen);
        full.setContentDescription(on?"Leave full screen":"Full screen");
    }
    // The camera-mode chip: a status dot (Home) or a lock (during a run), and ▾ when it opens the mode sheet. On the capture tabs
    // it takes taps (the mode sheet, or why it is locked); on Home a tap goes through to the picture (full screen).
    void setChip(String text,int dotColor,boolean locked,boolean opens,boolean tappable) {
        if(text==null) { chip.setVisibility(View.GONE); setRunning(running); return; }
        chip.setText(text); chip.setVisibility(View.VISIBLE);
        android.graphics.drawable.Drawable start=null;
        if(locked) start=small(R.drawable.ic_lock);
        else if(dotColor!=0) { GradientDrawable dot=new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(dotColor); dot.setBounds(0,0,M3.dp(context,8),M3.dp(context,8)); start=dot; }
        chip.setCompoundDrawablesRelative(start,null,opens?small(R.drawable.ic_expand_more):null,null);
        chip.setClickable(tappable);
        chip.setContentDescription("Camera mode "+text+(locked?", locked during the run":"")+(opens?". Tap to change.":""));
        setRunning(running);
    }
    // No picture: the status and, when the user has to act, the one button.
    void setPlaceholder(boolean on,String message,String label,Runnable onTap) {
        placeholder.setVisibility(on?View.VISIBLE:View.GONE);
        hud.setVisibility(on?View.GONE:View.VISIBLE);
        status.setText(message==null?"":message); status.setVisibility(message==null?View.GONE:View.VISIBLE);
        action.setVisibility(label==null?View.GONE:View.VISIBLE);
        if(label!=null) { action.setText(label); action.setOnClickListener(v -> onTap.run()); }
    }
    void showFlash() {
        flash.animate().cancel(); flash.setVisibility(View.VISIBLE); flash.setAlpha(0.85f);
        flash.animate().alpha(0f).setDuration(220).withEndAction(() -> flash.setVisibility(View.INVISIBLE)).start();
    }

    // Lays the picture out at the camera's aspect ratio inside whatever size the screen gives the box: centred, or, with an end
    // reserve, centred in the room left of it (flush with the start when it doesn't fit, so the column covers as little as
    // possible). The surface, flash and hud follow the picture; the placeholder fills the box.
    static final class Box extends FrameLayout {
        double aspect=16.0/9.0;
        int reserve;
        View picture,flash,hud,placeholder;
        private int left,top,right,bottom,hudRight;
        Box(Context c) { super(c); }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int w=MeasureSpec.getSize(widthSpec), h=MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(w,h);
            int pw=w, ph=(int)Math.round(w/aspect);
            if(ph>h) { ph=h; pw=(int)Math.round(h*aspect); }
            int room=w-reserve;
            left=reserve>0 && pw>room?0:((reserve>0?room:w)-pw)/2; top=(h-ph)/2; right=left+pw; bottom=top+ph;
            hudRight=reserve>0?Math.min(right,room):right;
            exactly(picture,pw,ph); exactly(flash,pw,ph); exactly(hud,hudRight-left,ph); exactly(placeholder,w,h);
        }
        private static void exactly(View v,int w,int h) {
            if(v.getVisibility()!=GONE) v.measure(MeasureSpec.makeMeasureSpec(Math.max(0,w),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,h),MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
            at(picture,left,top); at(flash,left,top); at(hud,left,top); at(placeholder,0,0);
        }
        private static void at(View v,int x,int y) { if(v.getVisibility()!=GONE) v.layout(x,y,x+v.getMeasuredWidth(),y+v.getMeasuredHeight());
        }
    }
}
