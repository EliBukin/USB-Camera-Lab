package com.anxel.usbcameralab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.widget.PopupMenu;
import com.google.android.material.button.MaterialButton;

// The Home tab, under the picture: the camera card (name, status, the one button when the user has to act, and its ⋯ menu with
// Camera info), a banner while
// a video or time-lapse runs, the camera mode (ModePanel), then the controls (ControlsPanel, whose view moves to the controls
// sheet while that is open) under a "Controls · Reset all" header. With no camera, a tip about Android's plug-in dialog.
@SuppressLint("SetTextI18n") // English-only UI built in code
final class HomeTab {
    final ScrollView view;
    final M3.Banner runBanner;
    final FrameLayout controlsSlot;   // the ControlsPanel's place on Home
    private final Context context;
    private final FrameLayout avatar;
    private final ImageView avatarIcon;
    private final TextView name, status, tip;
    private final MaterialButton action;
    private final View modeCard, controlsHeader;

    HomeTab(Context c,ModePanel mode,Runnable resetAll,Runnable cameraInfo) {
        context=c;
        LinearLayout list=M3.column(c); list.setPadding(0,M3.dp(c,10),0,M3.dp(c,12));
        // The camera card.
        LinearLayout card=M3.column(c); card.setPadding(M3.dp(c,14),M3.dp(c,10),M3.dp(c,12),M3.dp(c,10));
        LinearLayout top=M3.row(c);
        avatar=M3.avatar(c,R.drawable.ic_usb,0,0); avatarIcon=(ImageView)avatar.getChildAt(0);
        top.addView(avatar);
        LinearLayout words=M3.column(c);
        name=M3.title(c,"No camera"); status=M3.small(c,CameraSession.NO_CAMERA); status.setCompoundDrawablePadding(M3.dp(c,6));
        words.addView(name); words.addView(status);
        top.addView(words,M3.margins(c,0,-2,12,0,0,0)); ((LinearLayout.LayoutParams)words.getLayoutParams()).weight=1;
        MaterialButton more=M3.iconButton(c,R.drawable.ic_more_vert,"More options");
        more.setOnClickListener(v -> {
            PopupMenu menu=new PopupMenu(c,v);
            menu.getMenu().add("Camera info").setOnMenuItemClickListener(item -> { cameraInfo.run(); return true; });
            menu.show();
        });
        top.addView(more);
        card.addView(top);
        action=M3.filledButton(c,"",R.drawable.ic_refresh); action.setVisibility(View.GONE);
        LinearLayout.LayoutParams a=M3.margins(c,-2,-2,0,8,0,0); a.gravity=android.view.Gravity.END; card.addView(action,a);
        list.addView(M3.card(c,card),M3.margins(c,-1,-2,12,0,12,8));
        runBanner=new M3.Banner(c); list.addView(runBanner.view,M3.margins(c,-1,-2,12,0,12,8));
        // The camera mode.
        LinearLayout modeBox=M3.column(c); modeBox.setPadding(M3.dp(c,16),M3.dp(c,14),M3.dp(c,16),M3.dp(c,12));
        modeBox.addView(mode.view,new LinearLayout.LayoutParams(-1,-2));
        modeCard=M3.card(c,modeBox); list.addView(modeCard,M3.margins(c,-1,-2,12,0,12,4));
        // The controls.
        LinearLayout header=M3.row(c); controlsHeader=header;
        header.addView(M3.label(c,"Controls"),M3.grow());
        MaterialButton reset=M3.textButton(c,"Reset all",R.drawable.ic_refresh); reset.setContentDescription("Reset all controls to the camera's defaults");
        reset.setOnClickListener(v -> resetAll.run()); header.addView(reset);
        list.addView(header,M3.margins(c,-1,-2,24,0,8,0));
        controlsSlot=new FrameLayout(c); list.addView(controlsSlot,new LinearLayout.LayoutParams(-1,-2));
        tip=M3.small(c,"Tip: when you plug the camera in, Android offers to open USB Camera Lab. Tick \"Always\" and it connects straight away next time.");
        tip.setLineSpacing(0,1.15f); list.addView(tip,M3.margins(c,-1,-2,24,4,24,0));
        view=new ScrollView(c); view.addView(list,new ScrollView.LayoutParams(-1,-2));
        setOpen(false,false);
    }
    // The camera card: its name, a coloured dot and the status line, and when the user has to act, an error look with the button.
    void setCamera(String cameraName,String message,int dot,String label,Runnable onTap) {
        boolean error=label!=null;
        name.setText(error?firstSentence(message):cameraName==null?"No camera":cameraName);
        status.setText(error?rest(message):message);
        status.setVisibility(status.getText().length()==0?View.GONE:View.VISIBLE);
        GradientDrawable d=new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(dot); d.setBounds(0,0,M3.dp(context,8),M3.dp(context,8));
        status.setCompoundDrawablesRelative(error?null:d,null,null,null);
        ((GradientDrawable)avatar.getBackground()).setColor(M3.color(context,error?R.attr.colorErrorContainer:R.attr.colorPrimaryContainer));
        avatarIcon.setImageDrawable(M3.icon(context,error?R.drawable.ic_warning:R.drawable.ic_usb,M3.color(context,error?R.attr.colorOnErrorContainer:R.attr.colorOnPrimaryContainer)));
        action.setVisibility(error?View.VISIBLE:View.GONE);
        if(error) { action.setText(label); action.setOnClickListener(v -> onTap.run()); }
    }
    // "USB access to the camera was not allowed." as the title; a second sentence, if any, under it.
    private static String firstSentence(String s) { int dot=s.indexOf(". "); return dot<0?s:s.substring(0,dot+1); }
    private static String rest(String s) { int dot=s.indexOf(". "); return dot<0?"":s.substring(dot+2); }
    // OPEN: the mode and the controls; otherwise the tip.
    void setOpen(boolean open,boolean controls) {
        modeCard.setVisibility(open?View.VISIBLE:View.GONE);
        controlsHeader.setVisibility(open && controls?View.VISIBLE:View.GONE);
        tip.setVisibility(open?View.GONE:View.VISIBLE);
    }
}
