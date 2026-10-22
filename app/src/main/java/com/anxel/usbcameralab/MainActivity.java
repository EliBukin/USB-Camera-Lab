package com.anxel.usbcameralab;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.anxel.usbcameralab.CameraSession.State;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;
import com.google.android.material.snackbar.Snackbar;
import com.serenegiant.usb.Size;

// English-only UI built in code (no layouts, no string resources), Material 3 on AppCompat. Four tabs: Home (the camera, its
// mode and all the controls) and Photo, Video and Time-lapse, which choose the capture mode. One live picture stays on top of
// every tab (PreviewHost), so switching tabs, opening the sheets, rotating and full screen never touch the stream; Shell lays it
// all out. A navigation bar in portrait, a rail in landscape.
// The camera, its connection state machine, the stream mode and the capture live in CameraSession, for the whole process. This
// screen attaches while it is on screen (onStart..onStop): it renders the session, adds its preview surface, and builds the
// controls on the session's camera. A video or time-lapse goes on while the screen is away (CaptureService); coming back shows
// it as it is, with no reconnect, on its tab.
@SuppressLint("SetTextI18n")
public class MainActivity extends AppCompatActivity {
    private static final int HOME=0;
    private static final CaptureController.Mode[] TAB_MODES={null,CaptureController.Mode.PHOTO,CaptureController.Mode.VIDEO,CaptureController.Mode.TIMELAPSE};
    private static final String[] TAB_NAMES={"Home","Photo","Video","Time-lapse"};
    private static final int[] TAB_ICONS={R.drawable.ic_home,R.drawable.ic_photo_camera,R.drawable.ic_capture,R.drawable.ic_timer};
    private final int[] tabIds=new int[4];
    private CameraSession session;
    private CaptureController capture; // the session's
    private SharedPreferences prefs;   // the capture prefs file: also the remembered tab
    private Shell shell;
    private PreviewHost preview;
    private ControlsPanel controls;
    private ModePanel homeMode, sheetMode; // sheetMode while the mode sheet is open
    private HomeTab home;
    private CaptureBar captureBar;
    private ControlsSheet sheet;
    private BottomNavigationView nav;
    private NavigationRailView rail;
    private CoordinatorLayout snack;
    private int tab=-1;
    private boolean landscape, fullScreen;
    private String statusText=CameraSession.NO_CAMERA, actionLabel;
    private Runnable actionTap;
    private AlertDialog restoreDialog;
    private BottomSheetDialog modeSheet;

    @Override public void onCreate(Bundle saved) {
        EdgeToEdge.enable(this,SystemBarStyle.dark(0),SystemBarStyle.dark(0)); // transparent bars with light icons; Shell pads for them
        super.onCreate(saved);
        session=CameraSession.get(this); capture=session.capture;
        prefs=getSharedPreferences(CaptureController.PREFS,MODE_PRIVATE);
        preview=new PreviewHost(this,previewHost);
        controls=new ControlsPanel(this,session,() -> { captureBar.captureChanged(); render(); offerRestore(); }); // the lock option needs the controls
        homeMode=new ModePanel(this,session);
        home=new HomeTab(this,homeMode,() -> controls.resetAll());
        home.controlsSlot.addView(controls.view,new FrameLayout.LayoutParams(-1,-2));
        captureBar=new CaptureBar(this,preview,captureHost); captureBar.attach(capture);
        FrameLayout panels=new FrameLayout(this);
        for(View v:new View[]{home.view,captureBar.tabs.photo,captureBar.tabs.video,captureBar.tabs.timelapse}) panels.addView(v,new FrameLayout.LayoutParams(-1,-1));
        nav=new BottomNavigationView(this); rail=new NavigationRailView(this);
        for(int i=0;i<tabIds.length;i++) tabIds[i]=View.generateViewId();
        for(NavigationBarView bar:new NavigationBarView[]{nav,rail}) {
            Menu menu=bar.getMenu();
            for(int i=0;i<tabIds.length;i++) menu.add(Menu.NONE,tabIds[i],i,TAB_NAMES[i]).setIcon(TAB_ICONS[i]);
            bar.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);
            bar.setOnItemSelectedListener(item -> { selectTab(tabIndex(item.getItemId()),true); return true; });
            bar.setOnItemReselectedListener(item -> {});
        }
        snack=new CoordinatorLayout(this);
        ViewCompat.setOnApplyWindowInsetsListener(snack,(v,insets) -> WindowInsetsCompat.CONSUMED); // it sits inside the bars already
        sheet=new ControlsSheet(this,sheetHost);
        ViewCompat.setOnApplyWindowInsetsListener(sheet.host,(v,insets) -> WindowInsetsCompat.CONSUMED); // arrange() pads it
        shell=new Shell(this,preview,panels,captureBar.bar,nav,rail,snack,sheet.host);
        setContentView(shell);
        addContentView(captureBar.dim,new ViewGroup.LayoutParams(-1,-1)); // over everything while a time-lapse runs dimmed
        ViewCompat.setOnApplyWindowInsetsListener(shell,(v,insets) -> {
            shell.insets=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            sheet.arrange(landscape,shell.insets.bottom); shell.requestLayout();
            return insets;
        });
        getOnBackPressedDispatcher().addCallback(this,back);
        preview.surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { session.setPreview(h.getSurface()); }
            public void surfaceChanged(SurfaceHolder h,int f,int w,int height) {}
            public void surfaceDestroyed(SurfaceHolder h) { session.setPreview(null); }
        });
        int remembered=prefs.getInt("tab",HOME);
        selectTab(remembered>=0 && remembered<TAB_MODES.length?remembered:HOME,false);
    }
    // Rotation arrives here (manifest configChanges), so the stream keeps running.
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); arrange(); }
    // What shows where, for the orientation, full screen and the tab: Shell places it.
    private void arrange() {
        landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        boolean captureTab=tab>HOME;
        shell.landscape=landscape; shell.fullScreen=fullScreen; shell.capture=captureTab; shell.sheetOpen=sheet.isOpen() && landscape;
        nav.setVisibility(!landscape && !fullScreen?View.VISIBLE:View.GONE);
        rail.setVisibility(landscape && !fullScreen?View.VISIBLE:View.GONE);
        shell.panels.setVisibility(fullScreen || landscape && captureTab?View.GONE:View.VISIBLE);
        captureBar.bar.setVisibility(captureTab && !(landscape && sheet.isOpen())?View.VISIBLE:View.GONE);
        captureBar.arrange(landscape,fullScreen);
        preview.setFullScreen(fullScreen);
        sheet.arrange(landscape,shell.insets.bottom);
        shell.setBackgroundColor(fullScreen?0xff000000:M3.color(this,R.attr.colorSurface));
        updateSystemBars();
        render();
        shell.requestLayout();
    }
    // Hidden in full screen, and on the capture tabs in landscape, where the picture takes the full height; a swipe shows them
    // for a moment.
    private void updateSystemBars() {
        WindowInsetsControllerCompat bars=WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if(fullScreen || landscape && tab>HOME) bars.hide(WindowInsetsCompat.Type.systemBars()); else bars.show(WindowInsetsCompat.Type.systemBars());
    }
    // Full screen: only the picture, letterboxed on black, with the capture tab's shutter. A tap or Back returns. Only views
    // change, so the stream keeps running.
    private void setFullScreen(boolean on) {
        if(on==fullScreen) return;
        fullScreen=on;
        if(on) sheet.close();
        arrange();
    }
    // Back wakes a dimmed screen, then closes the controls sheet, then leaves full screen, then leaves the app as usual.
    private final OnBackPressedCallback back=new OnBackPressedCallback(true) {
        @Override public void handleOnBackPressed() {
            if(captureBar.wake()) return;
            if(sheet.isOpen()) { sheet.close(); return; }
            if(fullScreen) { setFullScreen(false); return; }
            setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true);
        }
    };

    // ---- Tabs ----
    private int tabIndex(int id) { for(int i=0;i<tabIds.length;i++) if(tabIds[i]==id) return i; return HOME; }
    private static int tabFor(CaptureController.Mode m) { for(int i=1;i<TAB_MODES.length;i++) if(TAB_MODES[i]==m) return i; return HOME; }
    // A capture tab sets the capture mode, unless a capture is busy: then it shows its shutter off, with "Go to …". Home never
    // changes it. The tab is remembered, as the capture mode was.
    private void selectTab(int t,boolean user) {
        if(t==tab) return;
        tab=t; prefs.edit().putInt("tab",t).apply();
        for(NavigationBarView bar:new NavigationBarView[]{nav,rail}) bar.getMenu().findItem(tabIds[t]).setChecked(true);
        home.view.setVisibility(t==HOME?View.VISIBLE:View.GONE);
        sheet.close();
        captureBar.show(TAB_MODES[t]);
        syncMode();
        if(user) { View shown=t==HOME?home.view:t==1?captureBar.tabs.photo:t==2?captureBar.tabs.video:captureBar.tabs.timelapse; shown.setAlpha(0f); shown.animate().alpha(1f).setDuration(150).start(); }
        arrange();
    }
    private void syncMode() { if(tab>HOME && !capture.isBusy() && capture.mode()!=TAB_MODES[tab]) capture.setMode(TAB_MODES[tab]); }
    // The running tab gets a red dot, on the bar and the rail.
    private void badges() {
        int running=capture.recording()?tabFor(capture.mode()):-1;
        for(NavigationBarView bar:new NavigationBarView[]{nav,rail}) for(int i=1;i<tabIds.length;i++) {
            if(i==running) { BadgeDrawable b=bar.getOrCreateBadge(tabIds[i]); b.setBackgroundColor(M3.REC); b.clearNumber(); b.setVisible(true); }
            else bar.removeBadge(tabIds[i]);
        }
    }

    // ---- What the screen shows of the session ----
    private void render() {
        if(shell==null) return; // still being built
        boolean open=session.state()==State.OPEN;
        Size mode=session.mode();
        UsbDevice d=session.selected();
        String name=d==null?null:d.getProductName()==null?"USB camera":d.getProductName();
        int dot=open?M3.OK:session.state()==State.IDLE?M3.color(this,R.attr.colorOutline):M3.color(this,R.attr.colorPrimary);
        home.setCamera(name,statusText,dot,actionLabel,actionTap);
        home.setOpen(open,!controls.isEmpty());
        boolean statusOnPicture=tab>HOME || fullScreen;
        preview.setPlaceholder(!open,statusOnPicture?statusText:null,statusOnPicture?actionLabel:null,actionTap);
        boolean busy=capture.isBusy();
        if(open && mode!=null) preview.setChip(ModePanel.chip(mode),tab==HOME && !capture.recording()?M3.OK:0,busy && capture.recording(),tab>HOME && !busy,tab>HOME);
        else preview.setChip(null,0,false,false,false);
        preview.full.setVisibility(open?View.VISIBLE:View.GONE);
        // With no camera the capture tabs look off (they still open and show the status on the picture).
        for(NavigationBarView bar:new NavigationBarView[]{nav,rail}) for(int i=1;i<tabIds.length;i++) { View item=bar.findViewById(tabIds[i]); if(item!=null) item.setAlpha(open?1f:0.38f); }
        badges();
        if(capture.recording()) {
            boolean lapse=capture.timelapseRunning();
            home.runBanner.show(this,0,R.drawable.ic_lock,"Camera mode is locked while the "+(lapse?"time-lapse runs":"video records")+". Controls stay live.","Go to "+(lapse?"Time-lapse":"Video"),() -> selectTab(tabFor(capture.mode()),true));
        } else home.runBanner.hide();
    }
    private void showMode() {
        Size mode=session.mode();
        if(mode!=null) { preview.setAspect((double)mode.width/mode.height); preview.surface.getHolder().setFixedSize(mode.width,mode.height); shell.requestLayout(); }
        homeMode.update(); if(sheetMode!=null) sheetMode.update();
        captureBar.captureChanged(); // the hints and sizes follow the mode
        render();
    }
    // The session's side of this screen.
    private final CameraSession.Ui screen=new CameraSession.Ui() {
        public void stateChanged(State next) {
            if(next==State.OPEN) { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); render(); return; }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            controls.clear(); homeMode.update();
            if(modeSheet!=null) modeSheet.dismiss();
            sheet.close();
            setFullScreen(false); // messages and Try again are on Home's card and the placeholder
            render();
        }
        public void status(String message) { statusText=message; render(); }
        public void showAction(String label,Runnable onTap) { actionLabel=label; actionTap=onTap; render(); }
        public void askCameraPermission() { requestPermissions(new String[]{Manifest.permission.CAMERA},1); }
        public void opened() { showMode(); controls.showSoon(); }
        public void modeChanged() { showMode(); controls.scheduleRefresh(); } // some cameras reset controls with the stream
        public void modePicked(Size m) { showMode(); }
    };
    @SuppressWarnings("deprecation") // the platform's permission dialog through Activity.requestPermissions, as before
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

    private final PreviewHost.Host previewHost=new PreviewHost.Host() {
        public void previewTapped() { setFullScreen(!fullScreen && session.state()==State.OPEN); }
        public void chipTapped() {
            if(tab==HOME || session.state()!=State.OPEN) return;
            if(capture.recording()) { message("Camera mode is locked while the "+(capture.timelapseRunning()?"time-lapse runs":"video records")+".",null,null); return; }
            openModeSheet();
        }
    };
    // From a capture tab: the camera mode in a sheet, with the tab's hint and its one-tap switch.
    private void openModeSheet() {
        if(modeSheet!=null) return;
        BottomSheetDialog d=new BottomSheetDialog(this);
        LinearLayout content=M3.column(this); content.setPadding(M3.dp(this,20),M3.dp(this,4),M3.dp(this,20),M3.dp(this,24));
        content.addView(M3.title(this,"Camera mode"),M3.margins(this,-1,-2,0,0,0,12));
        sheetMode=new ModePanel(this,session);
        content.addView(sheetMode.view,new LinearLayout.LayoutParams(-1,-2));
        CaptureBar.Hint hint=ModePanel.hint(session,TAB_MODES[tab]);
        if(hint!=null) {
            M3.Banner b=new M3.Banner(this); b.show(this,1,R.drawable.ic_info,hint.text,hint.action,() -> { hint.apply.run(); d.dismiss(); });
            content.addView(b.view,M3.margins(this,-1,-2,0,12,0,0));
        }
        d.setContentView(content);
        d.setOnDismissListener(x -> { modeSheet=null; sheetMode=null; });
        modeSheet=d; d.show();
    }
    private final ControlsSheet.Host sheetHost=new ControlsSheet.Host() {
        public void sheetChanged(boolean open,View panelLeft) {
            if(!open && panelLeft!=null) { M3.removeFromParent(panelLeft); home.controlsSlot.addView(panelLeft,new FrameLayout.LayoutParams(-1,-2)); }
            arrange();
        }
        public void resetAll() { controls.resetAll(); }
    };
    private final CaptureBar.Host captureHost=new CaptureBar.Host() {
        public void busyChanged() {
            if(shell==null) return; // CaptureBar reports once while it is being built
            syncMode();
            homeMode.update(); if(sheetMode!=null) sheetMode.update();
            render();
        }
        public CaptureBar.Hint hint(CaptureController.Mode m) { return ModePanel.hint(session,m); }
        public boolean canLock() { return controls!=null && controls.canLock(); }
        public void lockAuto() { controls.lockAuto(); }
        public void runEnded() { offerRestore(); }
        public void openControls() { if(session.state()==State.OPEN && !fullScreen) sheet.open(controls.view); }
        public void goTo(CaptureController.Mode m) { selectTab(tabFor(m),true); }
        public void message(String text,String action,Runnable onTap) { MainActivity.this.message(text,action,onTap); }
    };
    // Toasts are snackbars here, over the bottom of the tab's panel (or of the picture, where there is none).
    private void message(String text,String action,Runnable onTap) {
        Snackbar s=Snackbar.make(snack,text,action==null?Snackbar.LENGTH_LONG:6000);
        TextView t=s.getView().findViewById(com.google.android.material.R.id.snackbar_text); if(t!=null) t.setMaxLines(4);
        if(action!=null) s.setAction(action,v -> onTap.run());
        s.show();
    }
    // After a time-lapse, or at the next open of that camera when the run ended with it: offer to turn the auto modes back on.
    private void offerRestore() {
        String names=controls.lockedNames();
        if(names==null || restoreDialog!=null) return;
        restoreDialog=new MaterialAlertDialogBuilder(this).setTitle("Turn automatic settings back on?")
                .setMessage("The time-lapse turned off "+names+" to keep its frames steady.")
                .setPositiveButton("Turn back on",(dlg,w) -> controls.restoreAuto())
                .setNegativeButton("Keep manual",(dlg,w) -> controls.keepManual())
                .setCancelable(false).setOnDismissListener(dlg -> restoreDialog=null).show();
    }

    // Coming back during a run opens its tab.
    @Override protected void onStart() {
        super.onStart();
        if(capture.recording()) selectTab(tabFor(capture.mode()),false);
        session.attach(screen,captureBar);
    }
    // A run going on in the background comes back undimmed.
    @Override protected void onStop() { captureBar.wake(); session.detach(screen); super.onStop(); }
    // A run going on in the background keeps the session OPEN, so nothing cleared this screen's pending updates (the controls'
    // build and refresh).
    @Override protected void onDestroy() {
        controls.clear(); if(modeSheet!=null) modeSheet.dismiss(); if(restoreDialog!=null) restoreDialog.dismiss();
        session.forget(captureBar); captureBar.release(); super.onDestroy();
    }
}
