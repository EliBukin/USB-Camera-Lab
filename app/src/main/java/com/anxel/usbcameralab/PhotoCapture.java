package com.anxel.usbcameralab;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Debug;
import android.os.Handler;
import android.os.SystemClock;
import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ImageCapture;
import java.io.IOException;
import java.io.OutputStream;

// One photo. CameraHelper.takePicture renders the newest preview frame at the stream size and encodes a JPEG (quality from the
// helper's ImageCaptureConfig) into a pending MediaStore row, which is published on success and deleted otherwise. The library
// answers on the main, renderer or CameraHelper thread, or never (a capture still queued when the helper is released), so the
// result is handed to the main thread and a timeout ends a shot that gets no answer. Done runs exactly once, on main.
final class PhotoCapture {
    interface Done { void done(PhotoCapture shot,Uri saved,Bitmap thumbnail,String error); }
    static final long TIMEOUT_MS=5000;
    final CameraHelper owner; // the session it was taken on; the controller compares it like Events.stale()
    private final MediaFiles files;
    private final Handler main,io;
    private final Done done;
    private final long started=SystemClock.uptimeMillis(), time=System.currentTimeMillis();
    private final Sink sink=new Sink();
    private boolean finished; // main thread
    private final Runnable timeout=() -> finish("no answer from the camera in "+TIMEOUT_MS/1000+" s");

    PhotoCapture(CameraHelper owner,MediaFiles files,Handler main,Handler io,Done done) { this.owner=owner; this.files=files; this.main=main; this.io=io; this.done=done; }

    void start() {
        main.postDelayed(timeout,TIMEOUT_MS);
        // Our own stream instead of the library's MediaStore output: that one clears IS_PENDING even after a failed write
        // and reports errors without the Uri, so a broken row could never be deleted.
        owner.takePicture(new ImageCapture.OutputFileOptions.Builder(sink).build(),new ImageCapture.OnImageCaptureCallback() {
            public void onImageSaved(ImageCapture.OutputFileResults results) { main.post(() -> finish(null)); }
            public void onError(int code,String message,Throwable cause) { main.post(() -> finish("error "+code+": "+message)); }
        });
    }

    private void finish(String error) {
        if(finished) return; // a late answer after the timeout
        finished=true; main.removeCallbacks(timeout);
        long encoded=SystemClock.uptimeMillis()-started;
        io.post(() -> {
            Uri uri=sink.end(); String why=error; Bitmap thumb=null;
            if(why==null && sink.broken) why="couldn't write the photo";
            if(why==null && uri==null) why="the camera returned no image";
            if(why==null && !files.publish(uri)) why="couldn't publish the photo";
            if(why!=null) {
                if(uri!=null) files.discard(uri);
                android.util.Log.w("UsbCameraLab","photo failed after "+encoded+" ms: "+why);
            } else {
                thumb=files.thumbnail(uri);
                Runtime r=Runtime.getRuntime();
                android.util.Log.i("UsbCameraLab","photo "+files.imageSize(uri)+" "+sink.bytes/1024+" KB: encoded in "+encoded+" ms, saved in "+(SystemClock.uptimeMillis()-started)
                        +" ms; heap "+(r.totalMemory()-r.freeMemory())/1048576+"/"+r.maxMemory()/1048576+" MB, native "+Debug.getNativeHeapAllocatedSize()/1048576+" MB");
            }
            Uri saved=why==null?uri:null; Bitmap t=thumb; String w=why;
            main.post(() -> done.done(this,saved,t,w));
        });
    }

    // Opens the pending row at the first write, on the library's saver thread, so the frame is grabbed at the tap and a failed
    // capture leaves no row at all. end() waits for a write in progress, then refuses further ones.
    private final class Sink extends OutputStream {
        private OutputStream out; private Uri uri; private boolean ended; boolean broken; long bytes;
        @Override public synchronized void write(byte[] b,int off,int len) throws IOException {
            if(ended) throw new IOException("the shot has ended");
            // The library's saver catches only IOException; anything else would end its thread and the app.
            if(out==null) try { uri=files.create(MediaFiles.PHOTOS,MediaFiles.PHOTO_DIR,MediaFiles.name(time,".jpg"),"image/jpeg",time); out=files.open(uri); }
            catch(RuntimeException e) { throw new IOException(e); }
            out.write(b,off,len); bytes+=len;
        }
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b},0,1); }
        // The row to publish or discard, or null if nothing was written.
        synchronized Uri end() {
            ended=true;
            if(out!=null) try { out.close(); } catch(IOException e) { broken=true; android.util.Log.w("UsbCameraLab","close photo",e); }
            out=null; return uri;
        }
    }
}
