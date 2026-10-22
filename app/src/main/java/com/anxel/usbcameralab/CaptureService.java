package com.anxel.usbcameralab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

// Keeps a video or time-lapse going while the app is away: screen off, Home, another app in front. CameraSession owns the camera
// and the run; this service only puts the process in the foreground with an ongoing notification of the run (Stop finishes and
// saves the file, a tap opens the app) and holds a partial wake lock, so the phone doesn't sleep and stall the USB stream. It
// lives exactly as long as a run: started at the record tap, stopped when the file is finished.
// Started with startService while the app is on screen, and foreground at once in onStartCommand: Android 12+ refuses foreground
// starts from the background, and camera and microphone are while-in-use types, which only a service started from the foreground
// gets (Android 11+ records silence from a background mic without the microphone type). Not startForegroundService: when
// startForeground then fails, Android kills an app whose startForegroundService ends without it, while this one can just stop.
public final class CaptureService extends Service {
    static final String STOP="com.anxel.usbcameralab.action.STOP";
    private static final String CHANNEL="capture";
    private static final int RUN=1, RESULT=2; // notification ids: the run going on, and how a run ended while the app was away
    private static final long WAKE_MS=10*60_000L; // renewed with every notification update, so a hung run can't hold it for long
    private PowerManager.WakeLock wake;
    private boolean foreground, finished;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"UsbCameraLab:capture");
        wake.setReferenceCounted(false); // every acquire(timeout) just moves the timeout
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        CameraSession session=CameraSession.get(this);
        if(intent!=null && STOP.equals(intent.getAction())) { session.stopFromNotification(); if(!foreground) stopSelf(); return START_NOT_STICKY; }
        if(foreground || finished) return START_NOT_STICKY;
        Notification n=session.runNotification();
        if(n==null) { stopSelf(); return START_NOT_STICKY; } // the run ended before the service came up
        try {
            if(Build.VERSION.SDK_INT>=30) startForeground(RUN,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA|(session.recordsSound()?ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:0));
            else startForeground(RUN,n); // Android 10: the manifest's types; it has no while-in-use rule for them
        } catch(RuntimeException e) { // Android 12+: not from the background; 14+: a type without its permission
            android.util.Log.w("UsbCameraLab","capture: couldn't start the foreground service",e);
            finished=true; stopSelf(); session.serviceFailed();
            return START_NOT_STICKY;
        }
        foreground=true; wake.acquire(WAKE_MS);
        session.serviceStarted(this);
        return START_NOT_STICKY; // after a process death there is no run to come back to
    }
    @Override public void onDestroy() { if(wake.isHeld()) wake.release(); CameraSession.get(this).serviceGone(this); super.onDestroy(); }
    // A new text for the run's notification.
    void update(Notification n) {
        if(!foreground || finished) return;
        getSystemService(NotificationManager.class).notify(RUN,n);
        wake.acquire(WAKE_MS);
    }
    // The run is over: the notification goes, and so does the service.
    void finish() {
        if(finished) return;
        finished=true;
        if(wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    // The run's notification: what it is doing, and Stop while it can still be stopped. Silent, low importance.
    static Notification running(Context c,String title,String text,boolean stoppable) {
        Notification.Builder b=builder(c).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE);
        if(stoppable) {
            PendingIntent stop=PendingIntent.getService(c,1,new Intent(c,CaptureService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(c,R.drawable.ic_capture),"Stop",stop).build());
        }
        if(Build.VERSION.SDK_INT>=31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
    // A run that stopped by itself while the app was away (unplug, battery, storage, heat, an error): why, and what was saved.
    // note is the bar's toast, e.g. "Time-lapse stopped: the camera stopped. The video is saved."
    static void result(Context c,String note) {
        int colon=note.indexOf(": ");
        String title=colon<0?"USB Camera Lab":note.substring(0,colon), text=colon<0?note:note.substring(colon+2);
        text=Character.toUpperCase(text.charAt(0))+text.substring(1);
        Notification n=builder(c).setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build();
        c.getSystemService(NotificationManager.class).notify(RESULT,n);
    }
    static void clearResult(Context c) { c.getSystemService(NotificationManager.class).cancel(RESULT); }
    // A tap opens the app as the launcher would: back to the running screen, or a new one.
    private static Notification.Builder builder(Context c) {
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(manager.getNotificationChannel(CHANNEL)==null) {
            NotificationChannel channel=new NotificationChannel(CHANNEL,"Background capture",NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("A video or time-lapse that goes on while the app is away");
            channel.setSound(null,null); channel.enableVibration(false); channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Intent open=new Intent(c,MainActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_capture).setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(c,0,open,PendingIntent.FLAG_IMMUTABLE));
    }
}
