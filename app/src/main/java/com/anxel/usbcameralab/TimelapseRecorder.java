package com.anxel.usbcameralab;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Surface;
import com.herohan.uvcapp.CameraHelper;
import com.serenegiant.usb.Size;
import java.io.IOException;
import java.util.Locale;

// One time-lapse: a frame every `interval` seconds, played at 30 fps, no sound. The library's renderer draws every camera frame,
// GPU-scaled like video, into our encoder's input Surface. The platform keeps one frame per interval before the encoder
// (KEY_MAX_FPS_TO_ENCODER) and retimes the kept ones to 30 fps (KEY_CAPTURE_RATE), so the app does nothing per camera frame.
// MediaMuxer writes parts of an hour each into pending MediaStore rows, each published when its file is finished: a crash
// loses one part at most. A new part starts at a key frame of the running encoder, so the camera never sees the switch.
// MediaCodec and the muxer run on the time-lapse's own thread; Events arrive on main. No Activity, so it can move into a service.
final class TimelapseRecorder {
    interface Events {
        void progress(TimelapseRecorder r,int frames,long ms);          // about once a second: frames kept in all parts, wall time
        void warning(TimelapseRecorder r,String message);              // e.g. the battery is getting low
        void partSaved(TimelapseRecorder r,Uri saved,Bitmap thumb);    // an hour's part is published; the run goes on in the next
        // Exactly once. saved is the last part, or null when it kept nothing; why is the error then, else why the run stopped
        // (null: stop()). parts counts the published files, earlier parts included.
        void finished(TimelapseRecorder r,Uri saved,Bitmap thumb,int parts,String why);
    }
    static final int FPS=30;
    static final int[] INTERVALS={1,2,5,10,30,60,300}; // seconds
    private static final String MIME=MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long PART_MS=60*60_000L, TICK_MS=1000, EOS_WAIT_MS=3000;
    private static final int SPACE_EVERY=5, BATTERY_EVERY=10, LOG_EVERY=600; // ticks between checks, and between status lines in the log
    private static final int BATTERY_WARN=20, BATTERY_STOP=10;  // percent, while not charging
    final CameraHelper owner; // the session it records; the controller compares it like Events.stale()
    final int width,height,bitrate,interval;
    private final MediaFiles files;
    private final Handler main,work;
    private final HandlerThread thread=new HandlerThread("timelapse");
    private final Events events;
    private final PowerManager power;
    private final BatteryManager battery;
    private final long time=System.currentTimeMillis();
    private final Runnable tick=this::tick, eosTimeout=() -> { android.util.Log.w("UsbCameraLab","time-lapse: no end of stream from the encoder"); close(); };
    // Time-lapse thread only.
    private MediaCodec codec;
    private Surface surface;
    private MediaFormat format; // the encoder's output format, for every part's muxer
    private MediaMuxer muxer;
    private int track=-1;
    private Uri uri;
    private ParcelFileDescriptor fd;
    private int part, published, frames, partFrames;
    private long startedAt, partStartedAt;
    private boolean rotate;     // the part is an hour old: the next key frame starts a new one
    private boolean stopping, ended, warned;
    private String why, error;
    private int ticks;
    private long firstRaw=-1, lastRaw, minStep=Long.MAX_VALUE, maxStep; // the platform's timestamps, for the log
    private Uri lastSaved; private Bitmap lastThumb;
    private final PowerManager.OnThermalStatusChangedListener heat=status -> { if(status>=PowerManager.THERMAL_STATUS_SEVERE) end("the phone is too hot"); };

    TimelapseRecorder(Context context,CameraHelper owner,MediaFiles files,Size stream,int interval,Handler main,Events events) {
        this.owner=owner; this.files=files; this.interval=interval; this.main=main; this.events=events;
        int[] size=VideoRecorder.fit(stream.width,stream.height); width=size[0]; height=size[1];
        bitrate=(int)Math.round(16_000_000.0*width*height/(1920*1080)); // frames differ more than in video: 1080p ≈ 16 Mbps
        power=context.getSystemService(PowerManager.class);
        battery=context.getSystemService(BatteryManager.class);
        thread.start(); work=new Handler(thread.getLooper());
    }
    // "5 s", "1 min"
    static String every(int seconds) { return seconds%60==0?seconds/60+" min":seconds+" s"; }
    // Video length from frame count at 30 fps, "0:05".
    static String length(int frames) { int s=frames/FPS; return String.format(Locale.US,"%d:%02d",s/60,s%60); }
    void start() { work.post(this::begin); }
    // reason: why the run stops, for the user; null when they stopped it. Later calls are ignored.
    void stop(String reason) { work.post(() -> end(reason)); }

    private void begin() {
        try {
            if(power.getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_SEVERE) throw new IOException("the phone is too hot");
            String low=batteryLow(); if(low!=null) throw new IOException(low);
            open(time);
            MediaFormat f=MediaFormat.createVideoFormat(MIME,width,height);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);
            f.setInteger(MediaFormat.KEY_FRAME_RATE,FPS);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1); // a second of the finished video
            f.setInteger(MediaFormat.KEY_MAX_B_FRAMES,0);     // output order = input order, so frame n gets n/30 s below
            // The platform's time-lapse: keep the first frame at or after each interval, stamp kept frames 1/30 s apart.
            // No KEY_MAX_PTS_GAP_TO_ENCODER: a negative gap turns the frame limit off.
            f.setFloat(MediaFormat.KEY_CAPTURE_RATE,1f/interval);
            f.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,1f/interval);
            codec=MediaCodec.createEncoderByType(MIME);
            preferHigh(f);
            codec.setCallback(output,work);
            codec.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface=codec.createInputSurface();
            codec.start();
            owner.addSurface(surface,true);
            startedAt=partStartedAt=SystemClock.elapsedRealtime();
            power.addThermalStatusListener(work::post,heat);
            android.util.Log.i("UsbCameraLab",String.format(Locale.US,"time-lapse: every %s, %dx%d at %d fps, %.1f Mbps, encoder %s; %.1f GB free, battery %d%%",
                    every(interval),width,height,FPS,bitrate/1e6,codec.getName(),MediaFiles.freeBytes(fd.getFileDescriptor())/1e9,battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)));
            work.postDelayed(tick,TICK_MS);
        } catch(IOException|RuntimeException e) {
            android.util.Log.w("UsbCameraLab","time-lapse: couldn't start",e);
            error="couldn't start ("+e.getMessage()+")"; release(); closePart();
            finish();
        }
    }
    // High profile when the encoder offers it for 1080p30 (level 4): a better picture than its default, Baseline, at the same bitrate.
    private void preferHigh(MediaFormat f) {
        for(MediaCodecInfo.CodecProfileLevel p:codec.getCodecInfo().getCapabilitiesForType(MIME).profileLevels)
            if(p.profile==MediaCodecInfo.CodecProfileLevel.AVCProfileHigh && p.level>=MediaCodecInfo.CodecProfileLevel.AVCLevel4) {
                f.setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                f.setInteger(MediaFormat.KEY_LEVEL,MediaCodecInfo.CodecProfileLevel.AVCLevel4);
                return;
            }
    }
    // A new pending row for the next part; its muxer starts once the encoder's format is known.
    private void open(long at) throws IOException {
        part++;
        uri=files.create(MediaFiles.VIDEOS,MediaFiles.VIDEO_DIR,MediaFiles.name("TL_",time,"_"+every(interval).replace(" ","")+(part>1?"_part"+part:"")+".mp4"),"video/mp4",at);
        fd=files.openFd(uri);
        long free=MediaFiles.freeBytes(fd.getFileDescriptor());
        if(free>=0 && free<VideoRecorder.MIN_FREE) throw new IOException("storage is almost full");
        partFrames=0;
        if(format!=null) startMuxer();
    }
    private void startMuxer() throws IOException {
        muxer=new MediaMuxer(fd.getFileDescriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        track=muxer.addTrack(format); muxer.start();
    }
    private final MediaCodec.Callback output=new MediaCodec.Callback() {
        public void onInputBufferAvailable(MediaCodec c,int index) {} // Surface input
        public void onOutputFormatChanged(MediaCodec c,MediaFormat f) {
            if(c!=codec || format!=null) return;
            format=f;
            try { startMuxer(); } catch(IOException|RuntimeException e) { fail("couldn't write the file",e); }
        }
        public void onOutputBufferAvailable(MediaCodec c,int index,MediaCodec.BufferInfo info) {
            if(c!=codec) return;
            try {
                if((info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0 && info.size>0) write(c,index,info);
                c.releaseOutputBuffer(index,false);
                if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0) close();
            } catch(IOException|RuntimeException e) { fail("couldn't write the file",e); }
        }
        public void onError(MediaCodec c,MediaCodec.CodecException e) { if(c==codec) fail("encoder error",e); }
    };
    private void write(MediaCodec c,int index,MediaCodec.BufferInfo info) throws IOException {
        if(rotate && (info.flags&MediaCodec.BUFFER_FLAG_KEY_FRAME)!=0 && partFrames>0) { rotate=false; nextPart(); }
        if(muxer==null) return; // no format yet: can't happen once the encoder has output a frame
        long raw=info.presentationTimeUs;
        if(firstRaw<0) firstRaw=raw; else { long step=raw-lastRaw; minStep=Math.min(minStep,step); maxStep=Math.max(maxStep,step); }
        lastRaw=raw;
        // The platform already spaces kept frames 1/30 s apart; numbering them keeps every part's clock exact and starting at 0.
        info.presentationTimeUs=partFrames*1_000_000L/FPS;
        muxer.writeSampleData(track,c.getOutputBuffer(index),info);
        partFrames++; frames++;
    }
    private void nextPart() {
        long now=SystemClock.elapsedRealtime();
        android.util.Log.i("UsbCameraLab",String.format(Locale.US,"time-lapse: part %d done after %.1f min, %d frames",part,(now-partStartedAt)/60000.0,partFrames));
        if(!closePart()) { error="couldn't save part "+part; end(null); return; }
        Uri s=lastSaved; Bitmap t=lastThumb; main.post(() -> events.partSaved(this,s,t));
        partStartedAt=now;
        try { open(System.currentTimeMillis()); }
        catch(IOException e) { android.util.Log.w("UsbCameraLab","time-lapse: part "+part,e); end("couldn't start part "+part+" ("+e.getMessage()+")"); }
    }
    private void tick() {
        if(stopping) return;
        long now=SystemClock.elapsedRealtime(), ms=now-startedAt; int n=frames;
        main.post(() -> events.progress(this,n,ms));
        ticks++;
        if(ticks%SPACE_EVERY==0) {
            long free=MediaFiles.freeBytes(fd.getFileDescriptor());
            if(free>=0 && free<VideoRecorder.MIN_FREE) { end("storage is almost full"); return; }
        }
        if(ticks%BATTERY_EVERY==0) {
            String low=batteryLow(); if(low!=null) { end(low); return; }
        }
        if(ticks%LOG_EVERY==0) android.util.Log.i("UsbCameraLab",String.format(Locale.US,"time-lapse: %.0f min, %d frames, part %d, battery %d%%, thermal status %d",
                ms/60000.0,n,part,battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),power.getCurrentThermalStatus()));
        if(!rotate && now-partStartedAt>=PART_MS) {
            rotate=true; // the next key frame starts part+1; ask for one now rather than at the next second of video
            Bundle b=new Bundle(); b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0); codec.setParameters(b);
        }
        work.postDelayed(tick,TICK_MS);
    }
    // Why the run can't go on, or null. Below BATTERY_WARN a warning, once. A charging phone is never too low.
    private String batteryLow() {
        int level=battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        if(level<=0 || battery.isCharging()) return null; // unknown, or plugged in
        if(level<BATTERY_STOP) return "the battery is below "+BATTERY_STOP+" %";
        if(level<=BATTERY_WARN && !warned) {
            warned=true;
            String m="Battery at "+level+" %. The time-lapse stops below "+BATTERY_STOP+" %.";
            android.util.Log.w("UsbCameraLab","time-lapse: "+m);
            main.post(() -> events.warning(this,m));
        }
        return null;
    }
    private void fail(String message,Exception e) {
        android.util.Log.w("UsbCameraLab","time-lapse: "+message,e);
        if(error==null) error=message;
        if(stopping) close(); else end(null);
    }
    // Stop feeding the encoder first: removing our Surface makes the library paint it black, which must not become a frame.
    private void end(String reason) {
        if(stopping || ended) return; // ended: it never started, and a stop was already queued
        stopping=true; why=reason;
        work.removeCallbacks(tick); power.removeThermalStatusListener(heat);
        try { codec.signalEndOfInputStream(); work.postDelayed(eosTimeout,EOS_WAIT_MS); }
        catch(RuntimeException e) { close(); } // the encoder failed: finish what was written
    }
    // The encoder is done (end of stream, an error, or no answer in time): finish the part and report.
    private void close() {
        if(ended) return;
        work.removeCallbacks(eosTimeout);
        release();
        long ms=SystemClock.elapsedRealtime()-startedAt;
        android.util.Log.i("UsbCameraLab",String.format(Locale.US,"time-lapse: stopped after %.1f min (%s), %d frames in %d part(s); platform frame step %s",
                ms/60000.0,why==null?"user":why,frames,part,firstRaw<0||maxStep==0?"n/a":String.format(Locale.US,"%.2f-%.2f ms",minStep/1000.0,maxStep/1000.0)));
        closePart();
        finish();
    }
    private void release() {
        if(surface!=null) owner.removeSurface(surface); // posted to the renderer; it still holds its own reference
        if(codec!=null) { try { codec.stop(); } catch(RuntimeException ignored) {} codec.release(); codec=null; }
        if(surface!=null) { surface.release(); surface=null; }
    }
    // Finishes the current part's file: published when it has frames, else deleted. False when frames were lost.
    private boolean closePart() {
        boolean ok=true;
        if(muxer!=null) {
            if(partFrames>0) try { muxer.stop(); } catch(RuntimeException e) { android.util.Log.w("UsbCameraLab","time-lapse: muxer stop",e); ok=false; }
            muxer.release(); muxer=null;
        }
        long bytes=fd==null?0:Math.max(0,fd.getStatSize());
        if(fd!=null) try { fd.close(); } catch(IOException e) { ok=false; }
        fd=null;
        if(uri==null) return ok;
        if(ok && partFrames>0 && files.publish(uri)) {
            published++; lastSaved=uri; lastThumb=files.thumbnail(uri);
            android.util.Log.i("UsbCameraLab",String.format(Locale.US,"time-lapse part %d saved: %d frames, %d KB, %s",part,partFrames,bytes/1024,uri));
        } else {
            files.discard(uri);
            android.util.Log.i("UsbCameraLab","time-lapse part "+part+" not saved: "+(partFrames==0?"no frames":"couldn't finish the file"));
            if(partFrames>0) ok=false;
        }
        uri=null;
        return ok;
    }
    private void finish() {
        ended=true;
        Uri s=published>0?lastSaved:null; Bitmap t=s==null?null:lastThumb; int n=published;
        String w=error!=null?error:s==null?"no frames arrived":why;
        main.post(() -> events.finished(this,s,t,n,w));
        thread.quitSafely();
    }
}
