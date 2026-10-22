package com.anxel.usbcameralab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.graphics.Insets;

// The screen's root. It places, in this order from the bottom: the preview, the tab panels, the capture bar, the navigation bar
// (portrait) or rail (landscape), the snackbar host and the controls sheet, for portrait, landscape and full screen. Its
// children never change parent: the preview's SurfaceView would lose its surface (see PreviewHost).
//  Portrait: the picture at full width under the status bar (at most 60 % of the height), the tab's panel under it, the
//   capture bar at the panel's foot on the capture tabs, the navigation bar at the bottom. The sheet covers everything under
//   the picture, so every change shows on it.
//  Landscape, Home: rail, the picture in a left pane, the panel scrolling on the right.
//  Landscape, capture tabs (the Viewfinder layout): rail, the picture at full height (the system bars are hidden), and the
//   capture column at the end edge, over the picture where it doesn't fit beside it. The controls sheet opens at the end and
//   the picture moves left of it.
//  Full screen: the picture letterboxed on black over the whole window, with the capture bar or column of a capture tab.
@SuppressLint("ViewConstructor")
final class Shell extends ViewGroup {
    final PreviewHost preview;
    final View panels, bar, nav, rail, snack, sheet;
    boolean landscape, fullScreen, capture, sheetOpen;
    Insets insets=Insets.NONE;      // system bars and display cutout
    private final int barHeight, columnWidth, sheetWidth;
    // Where the last layout put things, for the views that need it (the sheet's height, the snackbar's room).
    int pictureBottom;

    Shell(Context c,PreviewHost preview,View panels,View bar,View nav,View rail,View snack,View sheet) {
        super(c);
        this.preview=preview; this.panels=panels; this.bar=bar; this.nav=nav; this.rail=rail; this.snack=snack; this.sheet=sheet;
        for(View v:new View[]{preview.box,panels,bar,nav,rail,snack,sheet}) addView(v);
        barHeight=M3.dp(c,112); columnWidth=M3.dp(c,96); sheetWidth=M3.dp(c,340);
        setBackgroundColor(M3.color(c,R.attr.colorSurface));
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int w=MeasureSpec.getSize(widthSpec), h=MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w,h);
        layoutChildren(w,h,true);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) { layoutChildren(r-l,b-t,false); }

    // One pass that either measures (measure=true) or places each visible child in its rectangle.
    private void layoutChildren(int w,int h,boolean measure) {
        Insets in=fullScreen?Insets.NONE:insets;
        if(fullScreen) {
            preview.setReserve(capture && landscape?columnWidth:0);
            place(preview.box,0,0,w,h,measure);
            if(capture) { if(landscape) place(bar,w-columnWidth,0,w,h,measure); else place(bar,0,h-barHeight,w,h,measure); }
            pictureBottom=h;
            place(snack,0,0,w,capture && !landscape?h-barHeight:h,measure);
            place(sheet,0,0,0,0,measure);
            return;
        }
        if(!landscape) {
            int navHeight=natural(nav,w,h);
            int top=in.top, width=w-in.left-in.right;
            int previewHeight=(int)Math.min(Math.round(width/preview.box.aspect),(h-top-navHeight)*0.6);
            preview.setReserve(0);
            place(preview.box,in.left,top,in.left+width,top+previewHeight,measure);
            pictureBottom=top+previewHeight;
            int foot=h-navHeight;
            place(nav,0,foot,w,h,measure);
            if(capture) place(bar,in.left,foot-barHeight,w-in.right,foot,measure);
            place(panels,in.left,pictureBottom,w-in.right,capture?foot-barHeight:foot,measure);
            place(snack,in.left,pictureBottom,w-in.right,capture?foot-barHeight:foot,measure);
            place(sheet,0,pictureBottom,w,h,measure);
            return;
        }
        int railWidth=natural(rail,w,h,true);
        place(rail,in.left,0,in.left+railWidth,h,measure);
        int x0=in.left+railWidth, x1=w-in.right, y0=in.top, y1=h-in.bottom;
        if(!capture) {
            int right=Math.max(M3.dp(getContext(),300),Math.min(M3.dp(getContext(),420),(x1-x0)*45/100));
            preview.setReserve(0);
            place(preview.box,x0,y0,x1-right,y1,measure);
            place(panels,x1-right,y0,x1,y1,measure);
            place(snack,x1-right,y0,x1,y1,measure);
            place(sheet,0,0,0,0,measure);
            pictureBottom=y1;
            return;
        }
        int side=Math.min(sheetWidth,(x1-x0)/2);
        preview.setReserve(sheetOpen?0:columnWidth);
        place(preview.box,x0,y0,sheetOpen?x1-side:x1,y1,measure);
        place(bar,x1-columnWidth,y0,x1,y1,measure);
        place(sheet,x1-side,y0,x1,y1,measure);
        place(snack,x0,y0,(sheetOpen?x1-side:x1-columnWidth),y1,measure);
        pictureBottom=y1;
    }
    private void place(View v,int l,int t,int r,int b,boolean measure) {
        if(v.getVisibility()==GONE) return;
        if(measure) v.measure(MeasureSpec.makeMeasureSpec(Math.max(0,r-l),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,b-t),MeasureSpec.EXACTLY));
        else v.layout(l,t,l+v.getMeasuredWidth(),t+v.getMeasuredHeight());
    }
    // A bar's own height (it pads itself for the system bars), or a rail's own width.
    private static int natural(View v,int w,int h) { return natural(v,w,h,false); }
    private static int natural(View v,int w,int h,boolean width) {
        if(v.getVisibility()==GONE) return 0;
        if(width) { v.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY)); return v.getMeasuredWidth(); }
        v.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.AT_MOST)); return v.getMeasuredHeight();
    }
    @Override public boolean shouldDelayChildPressedState() { return false; }
    @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() { return new ViewGroup.LayoutParams(-1,-1); }
}
