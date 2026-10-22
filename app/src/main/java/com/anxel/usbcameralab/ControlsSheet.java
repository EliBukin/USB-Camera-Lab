package com.anxel.usbcameralab;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDragHandleView;
import com.google.android.material.button.MaterialButton;

// The capture tabs' camera controls: the screen's ControlsPanel in a sheet that leaves the picture visible, so every change
// shows live. Portrait: a standard bottom sheet (no scrim) from the picture's lower edge down, dragged down or ✕ to close.
// Landscape: a side sheet at the end edge, and the picture moves left of it. The panel lives on Home while the sheet is closed.
final class ControlsSheet {
    interface Host {
        void sheetChanged(boolean open, View panelLeft); // open: the panel came here; closed: it may go back to Home
        void resetAll();
    }
    final CoordinatorLayout host;   // Shell lays it out over the area the sheet may cover
    private final LinearLayout sheet;
    private final NestedScrollView scroll;
    private final BottomSheetDragHandleView handle;
    private final Context context;
    private final Host owner;
    private BottomSheetBehavior<LinearLayout> behavior;
    private boolean open, landscape;
    private View panel;

    ControlsSheet(Context c,Host owner) {
        context=c; this.owner=owner;
        host=new CoordinatorLayout(c); host.setVisibility(View.GONE); host.setElevation(M3.dp(c,12)); // over the navigation bar
        sheet=M3.column(c); sheet.setClickable(true); sheet.setElevation(M3.dp(c,6));
        handle=new BottomSheetDragHandleView(c);
        sheet.addView(handle,new LinearLayout.LayoutParams(-1,M3.dp(c,24)));
        LinearLayout header=M3.row(c); header.setPadding(M3.dp(c,20),0,M3.dp(c,4),M3.dp(c,2));
        header.addView(M3.title(c,"Camera controls"),M3.grow());
        MaterialButton reset=M3.textButton(c,"Reset all",R.drawable.ic_refresh); reset.setContentDescription("Reset all controls to the camera's defaults");
        reset.setOnClickListener(v -> owner.resetAll()); header.addView(reset);
        MaterialButton close=M3.iconButton(c,R.drawable.ic_close,"Close the camera controls"); close.setOnClickListener(v -> close());
        header.addView(close);
        sheet.addView(header,new LinearLayout.LayoutParams(-1,-2));
        scroll=new NestedScrollView(c); scroll.setClipToPadding(false);
        sheet.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        host.addView(sheet,new CoordinatorLayout.LayoutParams(-1,-1));
    }
    boolean isOpen() { return open; }

    // Rotation: a bottom sheet in portrait, a side sheet in landscape. An open sheet stays open. Called on every arrange and
    // insets change: the behavior is made only when the sheet turns into a bottom sheet. A new behavior asks for the window
    // insets again at its first layout, so making one here each time would loop (and reset every drag).
    void arrange(boolean landscape,int bottomInset) {
        scroll.setPadding(0,0,0,landscape?M3.dp(context,8):bottomInset+M3.dp(context,8));
        if(landscape==this.landscape && (landscape || behavior!=null)) return;
        this.landscape=landscape;
        GradientDrawable back=new GradientDrawable(); back.setColor(M3.color(context,R.attr.colorSurfaceContainer));
        float r=M3.dp(context,28);
        back.setCornerRadii(landscape?new float[]{r,r,0,0,0,0,r,r}:new float[]{r,r,r,r,0,0,0,0});
        sheet.setBackground(back);
        handle.setVisibility(landscape?View.GONE:View.VISIBLE);
        sheet.setPadding(0,landscape?M3.dp(context,8):0,0,0);
        CoordinatorLayout.LayoutParams p=new CoordinatorLayout.LayoutParams(-1,-1); p.gravity=Gravity.BOTTOM;
        if(!landscape) {
            behavior=new BottomSheetBehavior<>(); behavior.setHideable(true); behavior.setSkipCollapsed(true); behavior.setFitToContents(true);
            behavior.setDraggable(true);
            behavior.setState(open?BottomSheetBehavior.STATE_EXPANDED:BottomSheetBehavior.STATE_HIDDEN);
            behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
                @Override public void onStateChanged(@NonNull View v,int state) { if(state==BottomSheetBehavior.STATE_HIDDEN && open) closed(); }
                @Override public void onSlide(@NonNull View v,float offset) {}
            });
            p.setBehavior(behavior);
        } else behavior=null;
        sheet.setLayoutParams(p);
        sheet.setTranslationX(0);
    }
    void open(View controls) {
        if(open) return;
        open=true; panel=controls;
        M3.removeFromParent(controls); scroll.addView(controls,new NestedScrollView.LayoutParams(-1,-2)); scroll.scrollTo(0,0);
        host.setVisibility(View.VISIBLE);
        owner.sheetChanged(true,null);
        if(behavior!=null) { behavior.setState(BottomSheetBehavior.STATE_HIDDEN); host.post(() -> { if(open && behavior!=null) behavior.setState(BottomSheetBehavior.STATE_EXPANDED); }); }
        else { sheet.setTranslationX(M3.dp(context,340)); sheet.animate().translationX(0).setDuration(220).start(); }
    }
    void close() {
        if(!open) return;
        if(behavior!=null && host.isLaidOut()) { behavior.setState(BottomSheetBehavior.STATE_HIDDEN); return; } // onStateChanged finishes
        if(landscape && host.isLaidOut()) { sheet.animate().translationX(sheet.getWidth()).setDuration(180).withEndAction(this::closed).start(); return; }
        closed();
    }
    private void closed() {
        if(!open) return;
        open=false; host.setVisibility(View.GONE); sheet.setTranslationX(0);
        View p=panel; panel=null; if(p!=null) scroll.removeView(p);
        owner.sheetChanged(false,p);
    }
}
