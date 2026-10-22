package com.anxel.usbcameralab;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

// Helpers for the Material 3 views this app builds in code (no layouts, no string resources): sizes, theme colours, text
// styles, icons, cards, banners and the pills that sit on the picture. Colours come from the theme (themes.xml); the few
// with no Material role are here.
final class M3 {
    static final int REC=0xffe53935, REC_TEXT=0xffff5a52, OK=0xff7bd88f;          // recording red, its text on dark, "camera open" green
    static final int WARN=0xff4f3b00, ON_WARN=0xffffdf9e, WARN_ACTION=0xffffd277; // battery and heat warnings
    static final int PILL=0x94000000, PILL_RED=0xd1a01410;                         // backings for text on the picture
    private M3() {}

    static int dp(Context c,float value) { return Math.round(value*c.getResources().getDisplayMetrics().density); }
    static int color(Context c,int attr) { return MaterialColors.getColor(c,attr,0xffff00ff); }
    static int style(Context c,int attr) { TypedValue v=new TypedValue(); c.getTheme().resolveAttribute(attr,v,true); return v.resourceId; }

    // Text in a Material type style ("TitleMedium", "BodySmall", …: com.google.android.material.R.attr.textAppearance*).
    static TextView text(Context c,CharSequence value,int appearance,int colorAttr) {
        TextView t=new TextView(c); t.setText(value);
        TextViewCompat.setTextAppearance(t,style(c,appearance));
        t.setTextColor(color(c,colorAttr));
        return t;
    }
    static TextView title(Context c,CharSequence value) { return text(c,value,R.attr.textAppearanceTitleMedium,R.attr.colorOnSurface); }
    static TextView body(Context c,CharSequence value) { return text(c,value,R.attr.textAppearanceBodyMedium,R.attr.colorOnSurface); }
    static TextView small(Context c,CharSequence value) { return text(c,value,R.attr.textAppearanceBodySmall,R.attr.colorOnSurfaceVariant); }
    // A section label in the primary colour: "CONTROLS", "INTERVAL".
    static TextView label(Context c,String value) {
        TextView t=text(c,value,R.attr.textAppearanceLabelMedium,R.attr.colorPrimary); t.setLetterSpacing(0.06f); t.setAllCaps(true); return t;
    }

    static Drawable icon(Context c,int res,int color) {
        Drawable d=AppCompatResources.getDrawable(c,res); if(d==null) return null;
        d=d.mutate(); d.setTint(color); return d;
    }
    static ImageView image(Context c,int res,int color,int sizeDp) {
        ImageView v=new ImageView(c); v.setImageDrawable(icon(c,res,color));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c,sizeDp),dp(c,sizeDp)));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }
    // A 40 dp round badge with an icon: the camera card's, the sound and output cards'.
    static FrameLayout avatar(Context c,int res,int background,int foreground) {
        FrameLayout f=new FrameLayout(c);
        GradientDrawable d=new GradientDrawable(); d.setShape(GradientDrawable.OVAL); d.setColor(background); f.setBackground(d);
        ImageView i=image(c,res,foreground,22); f.addView(i,new FrameLayout.LayoutParams(dp(c,22),dp(c,22),Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c,40),dp(c,40)));
        return f;
    }

    // A filled card on surfaceContainerLow with 16 dp corners, as in the mockups, holding a vertical list.
    static MaterialCardView card(Context c,LinearLayout content) {
        MaterialCardView card=new MaterialCardView(c,null,R.attr.materialCardViewFilledStyle);
        card.setCardBackgroundColor(color(c,R.attr.colorSurfaceContainerLow)); card.setRadius(dp(c,16)); card.setCardElevation(0);
        card.addView(content,new FrameLayout.LayoutParams(-1,-2));
        return card;
    }
    static LinearLayout column(Context c) { LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l=new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static LinearLayout.LayoutParams margins(Context c,int width,int height,int start,int top,int end,int bottom) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height);
        p.setMarginStart(dp(c,start)); p.topMargin=dp(c,top); p.setMarginEnd(dp(c,end)); p.bottomMargin=dp(c,bottom);
        return p;
    }
    static LinearLayout.LayoutParams grow() { return new LinearLayout.LayoutParams(0,-2,1); }

    static MaterialButton textButton(Context c,String label,int iconRes) {
        MaterialButton b=new MaterialButton(c,null,R.attr.borderlessButtonStyle); b.setText(label);
        if(iconRes!=0) b.setIconResource(iconRes);
        return b;
    }
    static MaterialButton filledButton(Context c,String label,int iconRes) {
        MaterialButton b=new MaterialButton(c,null,R.attr.materialButtonStyle); b.setText(label);
        if(iconRes!=0) b.setIconResource(iconRes);
        return b;
    }
    static MaterialButton tonalButton(Context c,String label,int iconRes) {
        MaterialButton b=new MaterialButton(c,null,R.attr.materialButtonTonalStyle); b.setText(label);
        if(iconRes!=0) b.setIconResource(iconRes);
        return b;
    }
    static MaterialButton iconButton(Context c,int iconRes,String description) {
        MaterialButton b=new MaterialButton(c,null,R.attr.materialIconButtonStyle); b.setIconResource(iconRes); b.setContentDescription(description);
        return b;
    }

    // A pill on the picture: white text on a dark (or red) translucent backing, so it reads over any image.
    static TextView pill(Context c,int backing) {
        TextView t=new TextView(c); t.setTextColor(0xffffffff); t.setTextSize(TypedValue.COMPLEX_UNIT_SP,13); t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        t.setGravity(Gravity.CENTER_VERTICAL); t.setMinHeight(dp(c,28)); t.setPadding(dp(c,10),dp(c,4),dp(c,10),dp(c,4));
        t.setCompoundDrawablePadding(dp(c,6));
        GradientDrawable d=new GradientDrawable(); d.setCornerRadius(dp(c,14)); d.setColor(backing); t.setBackground(d);
        return t;
    }

    // A tinted message with an icon and, optionally, one action under the text: hints (tonal), warnings, info and errors.
    static final class Banner {
        final LinearLayout view; final ImageView icon; final TextView text; final MaterialButton action;
        Banner(Context c) {
            view=row(c); view.setGravity(Gravity.TOP); view.setPadding(dp(c,12),dp(c,10),dp(c,8),dp(c,4));
            GradientDrawable back=new GradientDrawable(); back.setCornerRadius(dp(c,12)); view.setBackground(back);
            icon=image(c,R.drawable.ic_info,0,20); ((LinearLayout.LayoutParams)icon.getLayoutParams()).topMargin=dp(c,1);
            view.addView(icon);
            LinearLayout words=column(c); view.addView(words,margins(c,0,-2,10,0,0,0)); ((LinearLayout.LayoutParams)words.getLayoutParams()).weight=1;
            text=body(c,""); words.addView(text);
            action=textButton(c,"",0); action.setMinHeight(dp(c,48)); action.setMinimumHeight(dp(c,48)); action.setPadding(0,0,dp(c,8),0);
            action.setInsetTop(0); action.setInsetBottom(0);
            words.addView(action,new LinearLayout.LayoutParams(-2,-2));
            view.setVisibility(View.GONE);
        }
        // kind: 0 info (surfaceContainerHigh), 1 hint (secondaryContainer), 2 warning, 3 error
        void show(Context c,int kind,int iconRes,String message,String label,Runnable onTap) {
            int back, fore, act;
            if(kind==2) { back=WARN; fore=ON_WARN; act=WARN_ACTION; }
            else if(kind==3) { back=color(c,R.attr.colorErrorContainer); fore=color(c,R.attr.colorOnErrorContainer); act=fore; }
            else if(kind==1) { back=color(c,R.attr.colorSecondaryContainer); fore=color(c,R.attr.colorOnSecondaryContainer); act=color(c,R.attr.colorPrimary); }
            else { back=color(c,R.attr.colorSurfaceContainerHigh); fore=color(c,R.attr.colorOnSurface); act=color(c,R.attr.colorPrimary); }
            ((GradientDrawable)view.getBackground()).setColor(back);
            icon.setImageDrawable(M3.icon(c,iconRes,fore)); text.setText(message); text.setTextColor(fore);
            action.setVisibility(label==null?View.GONE:View.VISIBLE);
            view.setPadding(view.getPaddingLeft(),view.getPaddingTop(),view.getPaddingRight(),dp(c,label==null?10:2));
            if(label!=null) { action.setText(label); action.setTextColor(act); action.setOnClickListener(v -> onTap.run()); }
            view.setVisibility(View.VISIBLE);
        }
        void hide() { view.setVisibility(View.GONE); }
    }
    static void removeFromParent(View v) { if(v.getParent() instanceof ViewGroup) ((ViewGroup)v.getParent()).removeView(v); }
    static ColorStateList tint(int color) { return ColorStateList.valueOf(color); }
}
