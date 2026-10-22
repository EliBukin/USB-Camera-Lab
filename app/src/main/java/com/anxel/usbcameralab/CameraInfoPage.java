package com.anxel.usbcameralab;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialog;
import com.anxel.usbcameralab.CameraSession.State;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The Camera info page (Home's camera card, ⋯ > Camera info): a full-screen dialog over the main screen, not an activity, so
// MainActivity stays started, the preview keeps running behind it and the session's state machine never hears of it. On open,
// on Read again, and when the camera opens behind it, it reads the camera's descriptors through its own short-lived connection
// (UsbManager.openDevice: a separate file, read only, no interface claimed, closed right after) and, while the camera is open,
// runs the extension unit probe (XuProbe: GET requests only) on a background thread. Copy as text puts the whole report on
// the clipboard. Back or the close button closes it.
@SuppressLint("SetTextI18n") // English-only UI built in code
final class CameraInfoPage {
    private static final int PROBE_DELAY_MS=1500; // after the camera opens behind the page: its stream and controls come first
    private final AppCompatActivity activity;
    private final CameraSession session;
    private final ControlsPanel controls;
    private final UsbManager usb;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AppCompatDialog dialog;
    private LinearLayout list;
    private CameraInfo info;
    private String publicIds; // the usb.ids vendor IDs (17 KB), read once
    private volatile int generation;        // each read; results of an older one are dropped
    private volatile boolean probeAllowed;  // the probe's gate: the page is up and the camera is OPEN

    CameraInfoPage(AppCompatActivity activity,CameraSession session,ControlsPanel controls) {
        this.activity=activity; this.session=session; this.controls=controls;
        usb=(UsbManager)activity.getSystemService(Context.USB_SERVICE);
    }

    void show() {
        if(dialog!=null) return;
        Context c=activity;
        AppCompatDialog d=new AppCompatDialog(c,R.style.Theme_UsbCameraLab_FullScreenDialog);
        LinearLayout root=M3.column(c); root.setBackgroundColor(M3.color(c,R.attr.colorSurface));
        MaterialToolbar bar=new MaterialToolbar(c);
        bar.setTitle("Camera info");
        bar.setNavigationIcon(M3.icon(c,R.drawable.ic_close,M3.color(c,R.attr.colorOnSurface)));
        bar.setNavigationContentDescription("Close");
        bar.setNavigationOnClickListener(v -> d.dismiss());
        Menu menu=bar.getMenu();
        MenuItem again=menu.add(Menu.NONE,1,0,"Read again");
        again.setIcon(M3.icon(c,R.drawable.ic_refresh,M3.color(c,R.attr.colorOnSurfaceVariant))); again.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(Menu.NONE,2,1,"Copy as text").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM|MenuItem.SHOW_AS_ACTION_WITH_TEXT);
        bar.setOnMenuItemClickListener(item -> { if(item.getItemId()==1) read(); else copy(); return true; });
        root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        list=new Column(c,M3.dp(c,680)); list.setPadding(0,M3.dp(c,4),0,M3.dp(c,24));
        FrameLayout frame=new FrameLayout(c); frame.addView(list,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_HORIZONTAL));
        ScrollView scroll=new ScrollView(c); scroll.addView(frame,new ScrollView.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        d.setContentView(root);
        d.setOnDismissListener(x -> closed());
        dialog=d; d.show();
        android.util.Log.i("UsbCameraLab","camera info: open, camera "+session.state());
        read();
    }
    // MainActivity.onDestroy.
    void release() { if(dialog!=null) dialog.dismiss(); worker.shutdown(); }
    // MainActivity.onStop: the session may close the camera without telling this screen, so the probe stops first.
    void stop() { probeAllowed=false; ui.removeCallbacks(reread); }
    // The session's state changed behind the page: the probe stops when the camera leaves OPEN, and the page reads again once
    // the camera is open (or closed) for a moment.
    void sessionChanged() {
        if(dialog==null) return;
        State s=session.state();
        if(s!=State.OPEN) probeAllowed=false;
        ui.removeCallbacks(reread);
        if(s==State.OPEN || s==State.IDLE) ui.postDelayed(reread,s==State.OPEN?PROBE_DELAY_MS:500);
        else { info.state=s; render(); }
    }
    // The controls panel built its rows: which controls are shown follows.
    void controlsChanged() { if(dialog!=null) render(); }
    private final Runnable reread=this::read;

    private void closed() {
        probeAllowed=false; generation++; ui.removeCallbacksAndMessages(null);
        dialog=null; list=null;
        android.util.Log.i("UsbCameraLab","camera info: closed, camera "+session.state());
    }

    // What is known at once on the main thread; the descriptors and the probe follow from the worker.
    private void read() {
        if(dialog==null) return;
        int gen=++generation;
        if(publicIds==null) publicIds=loadIds();
        CameraInfo in=new CameraInfo(); info=in;
        State state=session.state();
        UsbDevice d=findCamera();
        in.device=d; in.state=state; in.mode=session.mode(); in.controls=controls; in.appVersion=appVersion(); in.publicIds=publicIds;
        in.permission=d!=null && usb.hasPermission(d); in.reading=in.permission;
        if(in.permission) try { in.serial=d.getSerialNumber(); } catch(SecurityException e) { in.serial=null; }
        AudioManager audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
        for(AudioDeviceInfo a:audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int t=a.getType();
            if(t==AudioDeviceInfo.TYPE_USB_DEVICE || t==AudioDeviceInfo.TYPE_USB_HEADSET || t==AudioDeviceInfo.TYPE_USB_ACCESSORY) in.audioInputs.add(String.valueOf(a.getProductName()));
        }
        // The probe borrows the library's own connection, so only while the camera is open.
        UsbControlBlock block=null;
        if(d!=null && state==State.OPEN && session.helper()!=null) {
            try { block=XuProbe.libraryConnection(session.helper(),d); in.probeState="reading…"; }
            catch(ReflectiveOperationException | RuntimeException e) {
                in.probeState="can't run: "+XuProbe.LIBRARY+"'s connection wasn't found ("+e.getClass().getSimpleName()+")";
                android.util.Log.w("UsbCameraLab","xu probe: no library connection",e);
            }
        } else in.probeState=d==null?"no camera":"runs while the camera is open (it uses the app's own connection to it)";
        probeAllowed=block!=null;
        render();
        android.util.Log.i("UsbCameraLab","camera info: reading"+(d==null?", no camera":" "+CameraSession.cameraKey(d))+", camera "+state+(block==null?", no probe":", probe on"));
        boolean permission=in.permission;
        UsbControlBlock probeBlock=block;
        worker.execute(() -> {
            if(gen!=generation) return;
            byte[] raw=null; String error=null;
            if(d!=null && permission) {
                UsbDeviceConnection c=null;
                try { c=usb.openDevice(d); if(c==null) error="Android didn't open the camera for reading its descriptors."; else raw=c.getRawDescriptors(); }
                catch(RuntimeException e) { error="Couldn't read the descriptors ("+e.getClass().getSimpleName()+")."; }
                finally { if(c!=null) c.close(); }
            } else if(d!=null) error="Allow USB access to the camera to read its descriptors.";
            UvcDescriptors desc=raw==null?null:UvcDescriptors.parse(raw);
            String rawError=error; byte[] read=raw;
            ui.post(() -> {
                if(gen!=generation) return;
                in.desc=desc; in.rawError=rawError; in.reading=false;
                android.util.Log.i("UsbCameraLab","camera info: descriptors "+(read==null?"not read"+(rawError==null?"":": "+rawError):read.length+" bytes"+(desc.truncated?" (cut short)":"")));
                render();
            });
            if(desc==null || probeBlock==null) return;
            for(UvcDescriptors.Unit xu:desc.units(UvcDescriptors.EXTENSION_UNIT)) {
                android.util.Log.i("UsbCameraLab","xu probe: unit "+xu.id+" "+xu.guid+", interface "+desc.vcInterface+", GET requests only");
                XuProbe.Result r=XuProbe.probe(probeBlock,desc.vcInterface,xu,() -> probeAllowed && gen==generation);
                android.util.Log.i("UsbCameraLab","xu probe: unit "+xu.id+" done, "+r.requests+" GET requests, 0 SET"+(r.stopped==null?"":", stopped: "+r.stopped));
                ui.post(() -> { if(gen==generation) { in.probe.add(r); render(); } });
            }
        });
    }
    private UsbDevice findCamera() {
        UsbDevice d=session.selected();
        if(d!=null) return d;
        for(UsbDevice x:usb.getDeviceList().values()) if(CameraSession.video(x)) return x;
        return null;
    }
    private String loadIds() {
        try(InputStream in=activity.getAssets().open("usb_vendor_ids.txt")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n;
            while((n=in.read(b))>0) out.write(b,0,n);
            return out.toString(StandardCharsets.US_ASCII.name());
        } catch(IOException e) { return null; }
    }
    @SuppressWarnings("deprecation") // getPackageInfo(String,int): the flags form needs API 33
    private String appVersion() {
        try { return activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName; }
        catch(PackageManager.NameNotFoundException e) { return null; }
    }

    private void copy() {
        if(info==null) return;
        String text=info.text();
        ClipboardManager clip=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clip.setPrimaryClip(ClipData.newPlainText("Camera info",text));
        android.util.Log.i("UsbCameraLab","camera info: copied "+text.length()+" characters");
        for(String line:text.split("\n")) android.util.Log.d("UsbCameraLab","camera info text: "+line);
        if(Build.VERSION.SDK_INT<=32) Snackbar.make(list,"Camera info copied as text",Snackbar.LENGTH_SHORT).show(); // Android 13+ confirms by itself
    }

    // ---- The cards ----
    private void render() {
        if(list==null || info==null) return;
        list.removeAllViews();
        for(CameraInfo.Section s:info.sections()) list.addView(card(s),M3.margins(activity,-1,-2,12,8,12,0));
    }
    private View card(CameraInfo.Section s) {
        Context c=activity;
        LinearLayout content=M3.column(c); content.setPadding(M3.dp(c,16),M3.dp(c,12),M3.dp(c,16),M3.dp(c,14));
        content.addView(M3.text(c,s.title,R.attr.textAppearanceTitleMedium,R.attr.colorOnSurface));
        if(s.summary!=null) content.addView(M3.small(c,s.summary),M3.margins(c,-1,-2,0,2,0,0));
        for(CameraInfo.Row r:s.rows) content.addView(row(r),M3.margins(c,-1,-2,0,10,0,0));
        for(String n:s.notes) { TextView t=M3.small(c,n); t.setLineSpacing(0,1.15f); content.addView(t,M3.margins(c,-1,-2,0,10,0,0)); }
        return M3.card(c,content);
    }
    // Label (and its detail) on the left, the value at the end; a long or multi-line value goes under the label.
    private View row(CameraInfo.Row r) {
        Context c=activity;
        TextView label=M3.body(c,r.label);
        TextView value=M3.text(c,r.value,R.attr.textAppearanceBodyMedium,R.attr.colorOnSurface);
        value.setTextColor(r.tone==CameraInfo.GOOD?M3.OK:r.tone==CameraInfo.WARN?M3.WARN_ACTION:r.tone==CameraInfo.MUTED?M3.color(c,R.attr.colorOnSurfaceVariant):M3.color(c,R.attr.colorOnSurface));
        if(r.mono) { value.setTypeface(Typeface.MONOSPACE); value.setTextSize(TypedValue.COMPLEX_UNIT_SP,13); }
        value.setTextIsSelectable(true);
        TextView detail=r.detail==null?null:M3.small(c,r.detail);
        boolean stacked=r.value.contains("\n") || r.value.length()>(r.mono?20:26);
        if(stacked) {
            LinearLayout box=M3.column(c);
            box.addView(label); box.addView(value,M3.margins(c,-1,-2,0,2,0,0));
            if(detail!=null) box.addView(detail,M3.margins(c,-1,-2,0,2,0,0));
            return box;
        }
        LinearLayout line=M3.row(c); line.setGravity(Gravity.TOP);
        LinearLayout words=M3.column(c); words.addView(label); if(detail!=null) words.addView(detail,M3.margins(c,-1,-2,0,1,0,0));
        line.addView(words,M3.grow());
        value.setGravity(Gravity.END); line.addView(value,M3.margins(c,-2,-2,12,0,0,0));
        return line;
    }
    // The cards' column: at most `max` wide, centred (landscape).
    @SuppressLint("ViewConstructor")
    private static final class Column extends LinearLayout {
        private final int max;
        Column(Context c,int max) { super(c); setOrientation(VERTICAL); this.max=max; }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            if(MeasureSpec.getSize(widthSpec)>max) widthSpec=MeasureSpec.makeMeasureSpec(max,MeasureSpec.EXACTLY);
            super.onMeasure(widthSpec,heightSpec);
        }
    }
}
