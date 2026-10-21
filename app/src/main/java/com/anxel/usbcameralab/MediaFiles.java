package com.anxel.usbcameralab;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// The app's files in shared storage, through MediaStore only (minSdk 29: no storage permission). A new file is a pending row,
// hidden from other apps until publish(); discard() deletes it. Blocking calls: use them off the main thread.
final class MediaFiles {
    static final Uri PHOTOS=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    static final String PHOTO_DIR="Pictures/USB Camera Lab";
    static final Uri VIDEOS=MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    static final String VIDEO_DIR="Movies/USB Camera Lab";
    private static final String PREFIX="USBCam_";
    private static boolean cleaned; // once per process: pending rows made later belong to captures still running
    private final ContentResolver resolver;
    MediaFiles(Context context) { resolver=context.getContentResolver(); }

    static String name(long time,String extension) { return PREFIX+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date(time))+extension; }
    // MediaStore adds " (1)" when the name is taken (two shots in one second).
    Uri create(Uri collection,String dir,String name,String mime,long time) throws IOException {
        ContentValues v=new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        v.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH,dir);
        v.put(MediaStore.MediaColumns.DATE_TAKEN,time);
        v.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=resolver.insert(collection,v);
        if(uri==null) throw new IOException("MediaStore refused "+name);
        return uri;
    }
    OutputStream open(Uri uri) throws IOException {
        OutputStream out=resolver.openOutputStream(uri,"w");
        if(out==null) throw new IOException("can't write "+uri);
        return out;
    }
    // "rw": MediaRecorder's MP4 writer seeks back to fill in box sizes.
    ParcelFileDescriptor openFd(Uri uri) throws IOException {
        ParcelFileDescriptor fd=resolver.openFileDescriptor(uri,"rw");
        if(fd==null) throw new IOException("can't write "+uri);
        return fd;
    }
    // Free bytes on the file system that holds fd, or -1 if unknown.
    static long freeBytes(FileDescriptor fd) {
        try { StructStatVfs s=Os.fstatvfs(fd); return s.f_bavail*s.f_frsize; }
        catch(ErrnoException e) { return -1; }
    }
    boolean publish(Uri uri) {
        ContentValues v=new ContentValues(); v.put(MediaStore.MediaColumns.IS_PENDING,0);
        try { return resolver.update(uri,v,null,null)==1; }
        catch(RuntimeException e) { android.util.Log.w("UsbCameraLab","publish "+uri,e); return false; }
    }
    void discard(Uri uri) {
        try { resolver.delete(uri,null,null); }
        catch(RuntimeException e) { android.util.Log.w("UsbCameraLab","discard "+uri,e); }
    }
    Bitmap thumbnail(Uri uri) {
        try { return resolver.loadThumbnail(uri,new android.util.Size(160,160),null); }
        catch(IOException|RuntimeException e) { android.util.Log.w("UsbCameraLab","thumbnail "+uri,e); return null; }
    }
    // "3840x2160", read from the JPEG header.
    String imageSize(Uri uri) {
        BitmapFactory.Options o=new BitmapFactory.Options(); o.inJustDecodeBounds=true;
        try(InputStream in=resolver.openInputStream(uri)) { BitmapFactory.decodeStream(in,null,o); } catch(IOException|RuntimeException e) { return "?"; }
        return o.outWidth+"x"+o.outHeight;
    }
    // The app's newest published photo or video (without a storage permission MediaStore lists only the app's own files), or null.
    Uri newest() {
        Uri[] collections={PHOTOS,VIDEOS}; String[] dirs={PHOTO_DIR,VIDEO_DIR};
        Uri best=null; long bestAdded=Long.MIN_VALUE;
        for(int i=0;i<collections.length;i++) {
            try(Cursor c=resolver.query(collections[i],new String[]{MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DATE_ADDED},MediaStore.MediaColumns.RELATIVE_PATH+"=?",new String[]{dirs[i]+"/"},MediaStore.MediaColumns._ID+" DESC")) {
                if(c!=null && c.moveToFirst() && c.getLong(1)>bestAdded) { bestAdded=c.getLong(1); best=ContentUris.withAppendedId(collections[i],c.getLong(0)); }
            } catch(RuntimeException e) { android.util.Log.w("UsbCameraLab","newest in "+dirs[i],e); }
        }
        return best;
    }
    static boolean isVideo(Uri uri) { return uri.toString().startsWith(VIDEOS.toString()); }
    // Rows a crash or kill left pending; they would otherwise linger until MediaStore expires them (about a week).
    @SuppressWarnings("deprecation") // setIncludePending: Android 10 has no QUERY_ARG_MATCH_PENDING
    void deleteLeftovers() {
        synchronized(MediaFiles.class) { if(cleaned) return; cleaned=true; }
        for(Uri collection:new Uri[]{PHOTOS,VIDEOS}) {
            Bundle q=new Bundle();
            q.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,MediaStore.MediaColumns.IS_PENDING+"=1 AND "+MediaStore.MediaColumns.DISPLAY_NAME+" LIKE ?");
            q.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{PREFIX+"%"});
            Uri from=collection;
            if(Build.VERSION.SDK_INT>=30) q.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE); else from=MediaStore.setIncludePending(collection);
            try(Cursor c=resolver.query(from,new String[]{MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME},q,null)) {
                while(c!=null && c.moveToNext()) {
                    android.util.Log.i("UsbCameraLab","deleting unfinished "+c.getString(1));
                    discard(ContentUris.withAppendedId(collection,c.getLong(0)));
                }
            } catch(RuntimeException e) { android.util.Log.w("UsbCameraLab","pending cleanup",e); }
        }
    }
}
