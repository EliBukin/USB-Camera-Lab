package com.anxel.usbcameralab;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.view.Surface;
import com.herohan.uvcapp.CameraHelper;
import com.serenegiant.usb.Size;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

// One video. MediaRecorder takes its frames from a Surface that the library's renderer draws every camera frame into, scaled on
// the GPU to the recorder's size, and writes an MP4 into a pending MediaStore row: published when the file is finished, deleted
// when no frame arrived. Not the library's startRecording: it encodes at the stream size (4K fails on the A20) and leaks its
// encoders. MediaRecorder runs on the recording's own thread; Events arrive on main. No Activity, so it can move into a service.
final class VideoRecorder {
    interface Events {
        void progress(VideoRecorder r,long ms,long bytes);  // about once a second
        void sound(VideoRecorder r,String source);          // "camera mic", "phone mic", … or "off (why)"
        // Exactly once. saved is null when nothing was kept; why is the error then, else why recording stopped (null: stop()).
        void finished(VideoRecorder r,Uri saved,Bitmap thumb,String why);
    }
    // The A20's H.264 encoder stops at 1920 × 1088. Fitting 1080 lines gives 1920 × 1080 for 16:9 and 1440 × 1080 for 4:3.
    static final int MAX_WIDTH=1920, MAX_HEIGHT=1080;
    static final long MIN_FREE=500_000_000L; // bytes; time-lapse uses it too
    private static final long TICK_MS=1000;
    private static final int SPACE_EVERY=5; // ticks between free-space checks
    // USB mics that sent only exact zeros this run. A working mic always has a noise floor; zeros mean the device sends nothing,
    // e.g. muted in its own controls, which Android never changes. The A20 lists, routes and reads the XC-TECH camera's mic and
    // gets only zeros, so its recordings switch to the phone's mic after SILENT_TICKS, and later ones start there.
    private static final Set<String> SILENT=Collections.synchronizedSet(new HashSet<>());
    private static final int SILENT_TICKS=2;
    final CameraHelper owner; // the session it records; the controller compares it like Events.stale()
    final int width,height,fps,bitrate;
    private final Context context;
    private final MediaFiles files;
    private final String camera; // the camera's USB product name, to find its microphone
    private final boolean sound; // RECORD_AUDIO is granted
    private final Handler main,work;
    private final HandlerThread thread=new HandlerThread("video");
    private final Events events;
    private final PowerManager power;
    private final long time=System.currentTimeMillis();
    private final Runnable tick=this::tick;
    // Recording thread only.
    private MediaRecorder recorder;
    private Surface surface;
    private Uri uri;
    private ParcelFileDescriptor fd;
    private boolean ended;
    private long startedAt;
    private int ticks;
    private String soundSource;
    private AudioDeviceInfo listening; // the USB mic recording now, until it is heard or replaced
    private int quiet;                 // ticks of silence from it
    private String silenceNote="";     // added to the sound source once a silent USB mic was passed over
    private final PowerManager.OnThermalStatusChangedListener heat=status -> { if(status>=PowerManager.THERMAL_STATUS_SEVERE) end("the phone is too hot"); };

    VideoRecorder(Context context,CameraHelper owner,MediaFiles files,Size stream,String camera,boolean sound,Handler main,Events events) {
        this.context=context; this.owner=owner; this.files=files; this.camera=camera; this.sound=sound; this.main=main; this.events=events;
        int[] size=fit(stream.width,stream.height); width=size[0]; height=size[1];
        fps=stream.fps>0?stream.fps:30;
        bitrate=(int)Math.min(40_000_000L,Math.round(0.2*width*height*fps)); // 0.2 bits per pixel per frame: 1080p30 ≈ 12.4 Mbps
        power=context.getSystemService(PowerManager.class);
        thread.start(); work=new Handler(thread.getLooper());
    }
    // The stream size scaled into MAX_WIDTH × MAX_HEIGHT with its aspect ratio, both sides even.
    static int[] fit(int w,int h) {
        double scale=Math.min(1,Math.min(MAX_WIDTH/(double)w,MAX_HEIGHT/(double)h));
        return new int[]{(int)Math.round(w*scale)&~1,(int)Math.round(h*scale)&~1};
    }
    void start() { work.post(this::begin); }
    // reason: why recording stops, for the user; null when they stopped it. Later calls are ignored.
    void stop(String reason) { work.post(() -> end(reason)); }

    private void begin() {
        try {
            uri=files.create(MediaFiles.VIDEOS,MediaFiles.VIDEO_DIR,MediaFiles.name(time,".mp4"),"video/mp4",time);
            fd=files.openFd(uri);
            long free=MediaFiles.freeBytes(fd.getFileDescriptor());
            if(free>=0 && free<MIN_FREE) throw new IOException("storage is almost full");
            if(power.getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_SEVERE) throw new IOException("the phone is too hot");
            String silent=sound?null:"off (no microphone permission)";
            try { record(sound); }
            catch(IOException|RuntimeException e) {
                if(!sound) throw e;
                android.util.Log.w("UsbCameraLab","video: can't record with sound, trying without",e);
                drop(); Os.ftruncate(fd.getFileDescriptor(),0);
                record(false); silent="off (microphone unavailable)";
            }
            startedAt=SystemClock.elapsedRealtime();
            power.addThermalStatusListener(work::post,heat);
            android.util.Log.i("UsbCameraLab",String.format(Locale.US,"video: recording %dx%d at %d fps, %.1f Mbps, sound %s; %.1f GB free",width,height,fps,bitrate/1e6,silent==null?"on":silent,free/1e9));
            if(silent!=null) report(silent); else routed();
            work.postDelayed(tick,TICK_MS);
        } catch(IOException|RuntimeException|ErrnoException e) {
            android.util.Log.w("UsbCameraLab","video: couldn't start",e);
            drop(); finish(null,"couldn't start ("+e.getMessage()+")");
        }
    }
    @SuppressWarnings("deprecation") // MediaRecorder(Context) needs API 31
    private void record(boolean withSound) throws IOException {
        recorder=Build.VERSION.SDK_INT>=31?new MediaRecorder(context):new MediaRecorder();
        recorder.setOnErrorListener((r,what,extra) -> end("recorder error "+what+"/"+extra)); // on this thread: it created the recorder
        AudioDeviceInfo mic=withSound?pickMic():null;
        if(withSound) recorder.setAudioSource(MediaRecorder.AudioSource.MIC); // CAMCORDER would prefer the phone's mics over USB
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        // MediaRecorder has no key-frame interval setting; its default is one second.
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(width,height); recorder.setVideoFrameRate(fps); recorder.setVideoEncodingBitRate(bitrate);
        if(withSound) {
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(48000); recorder.setAudioEncodingBitRate(128000);
            recorder.setAudioChannels(mic!=null && stereo(mic)?2:1);
            if(mic!=null) recorder.setPreferredDevice(mic);
            recorder.addOnRoutingChangedListener(r -> routed(),work);
            android.util.Log.i("UsbCameraLab","video: preferred mic "+(mic==null?"none (phone)":mic.getProductName()+" type "+mic.getType()+" channels "+Arrays.toString(mic.getChannelCounts())));
        }
        recorder.setOutputFile(fd.getFileDescriptor());
        recorder.prepare();
        surface=recorder.getSurface();
        owner.addSurface(surface,true); // frames drawn before start() are dropped
        recorder.start();
        if(mic!=null && mic.getType()==AudioDeviceInfo.TYPE_USB_DEVICE) { listening=mic; recorder.getMaxAmplitude(); } // the first call starts amplitude tracking
    }
    // The camera's own mic when Android lists it, else another USB mic. Otherwise null (Android's choice, the phone's mic when no
    // headset is plugged in), or the phone's mic by name when a silent USB mic is left out: MIC would route to it.
    private AudioDeviceInfo pickMic() {
        AudioDeviceInfo usb=null, phone=null;
        for(AudioDeviceInfo d:context.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC) { if(phone==null) phone=d; }
            else if(d.getType()!=AudioDeviceInfo.TYPE_USB_DEVICE) continue;
            else if(SILENT.contains(String.valueOf(d.getProductName()))) silenceNote=isCamera(d)?" (camera mic is silent)":" (USB mic is silent)";
            else if(isCamera(d)) return d;
            else if(usb==null) usb=d;
        }
        return usb!=null?usb:silenceNote.isEmpty()?null:phone;
    }
    private void listen() {
        if(recorder.getMaxAmplitude()>0) { listening=null; return; }
        if(++quiet<SILENT_TICKS) return;
        String name=String.valueOf(listening.getProductName());
        SILENT.add(name); listening=null;
        AudioDeviceInfo next=pickMic();
        android.util.Log.w("UsbCameraLab","video: only silence from "+name+" for "+quiet+" s, switching to "+(next==null?"Android's choice":next.getProductName()));
        recorder.setPreferredDevice(next); // MediaRecorder reroutes while recording
    }
    // Android names the camera's mic "USB-Audio - <USB product name>".
    private boolean isCamera(AudioDeviceInfo d) { return camera!=null && d.getProductName()!=null && d.getProductName().toString().contains(camera); }
    private static boolean stereo(AudioDeviceInfo d) { for(int c:d.getChannelCounts()) if(c==2) return true; return false; }
    private void routed() {
        AudioDeviceInfo d=recorder==null?null:recorder.getRoutedDevice();
        if(d==null) return; // not routed yet: the routing listener or the next tick asks again
        String source;
        switch(d.getType()) {
            case AudioDeviceInfo.TYPE_USB_DEVICE: case AudioDeviceInfo.TYPE_USB_HEADSET: source=isCamera(d)?"camera mic":"USB mic"; break;
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: source="phone mic"; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: source="headset mic"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: source="Bluetooth mic"; break;
            default: source=String.valueOf(d.getProductName());
        }
        if(d.getType()!=AudioDeviceInfo.TYPE_USB_DEVICE) source+=silenceNote;
        if(!source.equals(soundSource)) android.util.Log.i("UsbCameraLab","video: sound routed to "+d.getProductName()+" (type "+d.getType()+")");
        report(source);
    }
    private void report(String source) {
        if(source.equals(soundSource)) return;
        soundSource=source; main.post(() -> events.sound(this,source));
    }
    private void tick() {
        if(ended) return;
        long ms=SystemClock.elapsedRealtime()-startedAt, bytes=Math.max(0,fd.getStatSize());
        main.post(() -> events.progress(this,ms,bytes));
        if(soundSource==null) routed();
        if(listening!=null) listen();
        if(++ticks%SPACE_EVERY==0) {
            long free=MediaFiles.freeBytes(fd.getFileDescriptor());
            if(free>=0 && free<MIN_FREE) { end("storage is almost full"); return; }
        }
        work.postDelayed(tick,TICK_MS);
    }
    private void end(String reason) {
        if(ended) return;
        work.removeCallbacks(tick); power.removeThermalStatusListener(heat);
        long ms=SystemClock.elapsedRealtime()-startedAt;
        String error=null;
        // Stop before removing the surface: removal paints it black, which would otherwise be the video's last frame.
        try { recorder.stop(); } catch(RuntimeException e) { error="no video frames arrived"; } // what stop() does before the first frame
        drop();
        android.util.Log.i("UsbCameraLab",String.format(Locale.US,"video: stopped after %.1f s (%s)%s",ms/1000.0,reason==null?"user":reason,error==null?"":": "+error));
        finish(reason,error);
    }
    // Detach and free a recorder that is stopped or never started. Releasing our Surface is safe while the renderer still
    // holds it: its EGL surface keeps its own reference, and the removal posted here deletes that one.
    private void drop() {
        if(surface!=null) owner.removeSurface(surface);
        if(recorder!=null) { recorder.release(); recorder=null; }
        if(surface!=null) { surface.release(); surface=null; }
    }
    private void finish(String reason,String error) {
        ended=true;
        long bytes=fd==null?0:Math.max(0,fd.getStatSize());
        if(fd!=null) try { fd.close(); } catch(IOException e) { if(error==null) error="couldn't close the file"; }
        fd=null;
        Uri saved=null; Bitmap thumb=null;
        if(uri!=null) {
            if(error==null && files.publish(uri)) { saved=uri; thumb=files.thumbnail(uri); }
            else { if(error==null) error="couldn't publish the video"; files.discard(uri); }
        }
        android.util.Log.i("UsbCameraLab",saved!=null?"video saved: "+bytes/1024+" KB, "+uri:"video not saved: "+error);
        Uri s=saved; Bitmap t=thumb; String why=error!=null?error:reason;
        main.post(() -> events.finished(this,s,t,why));
        thread.quitSafely();
    }
}
