package com.anxel.usbcameralab;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.os.SystemClock;
import com.herohan.uvcapp.CameraHelper;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// A read-only look at a camera's extension units: vendor controls the UVC standard doesn't describe. It sends GET requests
// only, through get(), which refuses any other request code, and logs every request with its answer.
// Which connection: control requests to a unit go to the video control interface, and Linux usbfs only lets the file that
// claimed an interface talk to it. libuvc claims it on the connection of UVCAndroid's UVCCamera (UVCCamera.open clones the
// USBMonitor's control block and opens a second connection for the native code), so the probe borrows that one, found by
// reflection into UVCAndroid 1.0.13 (CameraHelper.mService -> its mCameras -> CameraInternal.mUVCCamera); if the library
// changes, the probe says it can't run. Each request holds that control block's lock, which UVCCamera.close also takes
// before closing the connection, so the library never closes it under a request; a request takes at most TIMEOUT_MS.
// Reads on endpoint 0 don't touch the streaming endpoint, as the controls panel's reads don't. Background thread only.
final class XuProbe {
    static final String LIBRARY="UVCAndroid 1.0.13"; // the version the reflection below is written for
    static final int GET_CUR=0x81, GET_MIN=0x82, GET_MAX=0x83, GET_LEN=0x85, GET_INFO=0x86, GET_DEF=0x87;
    private static final int CLASS_INTERFACE_IN=0xa1; // bmRequestType: device to host, class request, to an interface
    private static final int TIMEOUT_MS=200, MAX_LENGTH=512, STOP_AFTER_TIMEOUTS=3;
    // Whether the probe may go on: false once the camera leaves OPEN or the page closes.
    interface Gate { boolean open(); }

    // What one selector answered. A null value was not read (see its error, or the selector's).
    static final class Selector {
        final int selector; final boolean fromBits;
        int info=-1, length=-1;
        byte[] cur, min, max, def;
        String infoError, lengthError, curError, rangeError;
        Selector(int selector,boolean fromBits) { this.selector=selector; this.fromBits=fromBits; }
    }
    static final class Result {
        final int unit; final List<Selector> selectors=new ArrayList<>();
        String stopped; // why the probe ended early, or null
        int requests;
        Result(int unit) { this.unit=unit; }
    }

    // The library's control block for this camera, or an exception saying why there is none.
    static UsbControlBlock libraryConnection(CameraHelper helper,UsbDevice device) throws ReflectiveOperationException {
        Object service=field(CameraHelper.class,"mService").get(helper);
        if(service==null) throw new IllegalStateException("no camera service");
        Object lock=field(service.getClass(),"mConnectionSync").get(service);
        Collection<?> cameras;
        synchronized(lock==null?service:lock) { cameras=new ArrayList<>(((Map<?,?>)field(service.getClass(),"mCameras").get(service)).values()); }
        for(Object camera:cameras) {
            UVCCamera uvc=(UVCCamera)field(camera.getClass(),"mUVCCamera").get(camera);
            UsbControlBlock block=uvc==null?null:uvc.getUsbControlBlock();
            if(block!=null && device.equals(block.getDevice())) return block;
        }
        throw new IllegalStateException("the camera isn't open in the library");
    }
    private static Field field(Class<?> c,String name) throws NoSuchFieldException { Field f=c.getDeclaredField(name); f.setAccessible(true); return f; }

    // Selectors to read: the bits set in bmControls (bit n is selector n+1); none set, then 1..bNumControls.
    static List<Selector> selectors(UvcDescriptors.Unit xu) {
        List<Selector> r=new ArrayList<>();
        for(int bit:xu.bits()) r.add(new Selector(bit+1,true));
        if(r.isEmpty()) for(int s=1;s<=xu.numControls;s++) r.add(new Selector(s,false));
        return r;
    }

    // GET_INFO and GET_LEN for every selector; GET_CUR, GET_MIN, GET_MAX and GET_DEF where GET_INFO says it can be read.
    static Result probe(UsbControlBlock block,int vcInterface,UvcDescriptors.Unit xu,Gate gate) {
        Result r=new Result(xu.id);
        int timeouts=0;
        for(Selector s:selectors(xu)) {
            r.selectors.add(s);
            Answer a;
            int[] steps={GET_INFO,GET_LEN,GET_CUR,GET_MIN,GET_MAX,GET_DEF};
            for(int request:steps) {
                if(!gate.open()) { r.stopped="the camera closed or the page was left"; return r; }
                if(request==GET_CUR && (s.info<0 || s.length<0)) { s.curError=s.info<0?"not read: GET_INFO gave nothing":"not read: GET_LEN gave nothing"; break; }
                if(request==GET_CUR && (s.info&1)==0) { s.curError="GET_INFO says it can't be read"; break; }
                if(request==GET_CUR && s.length>MAX_LENGTH) { s.curError="not read: "+s.length+" bytes is more than this page reads"; break; }
                int length=request==GET_INFO?1:request==GET_LEN?2:s.length;
                a=get(block,request,xu.id,vcInterface,s.selector,length); r.requests++;
                if(a.closed) { r.stopped="the camera's connection closed"; return r; }
                timeouts=a.timedOut?timeouts+1:0;
                if(request==GET_INFO) { if(a.data!=null && a.data.length>=1) s.info=a.data[0]&0xff; else s.infoError=a.error; }
                else if(request==GET_LEN) { if(a.data!=null && a.data.length>=2) s.length=(a.data[0]&0xff)|(a.data[1]&0xff)<<8; else s.lengthError=a.error; }
                else if(request==GET_CUR) { s.cur=a.data; s.curError=a.error; }
                else {
                    if(request==GET_MIN) s.min=a.data; else if(request==GET_MAX) s.max=a.data; else s.def=a.data;
                    if(a.data==null && s.rangeError==null) s.rangeError=a.error;
                }
                if(timeouts>=STOP_AFTER_TIMEOUTS) { r.stopped="the camera stopped answering, so the probe stopped"; return r; }
            }
        }
        return r;
    }

    private static final class Answer { byte[] data; String error; boolean timedOut, closed; }
    // One class-specific GET to a unit of the video control interface: wValue = selector << 8, wIndex = unit << 8 | interface.
    // Only the GET codes 0x81..0x87 pass; there is no other way out of this class to the camera.
    private static Answer get(UsbControlBlock block,int request,int unit,int vcInterface,int selector,int length) {
        if(request<GET_CUR || request>GET_DEF) throw new IllegalArgumentException("the extension unit probe only sends GET requests");
        Answer a=new Answer();
        byte[] buffer=new byte[length];
        int value=selector<<8, index=unit<<8|vcInterface, n;
        long started=SystemClock.uptimeMillis();
        synchronized(block) { // UVCCamera.close takes it too: the connection stays open for this request
            UsbDeviceConnection c=block.getConnection();
            if(c==null) { a.closed=true; android.util.Log.i("UsbCameraLab","xu probe: connection closed, no request sent"); return a; }
            n=c.controlTransfer(CLASS_INTERFACE_IN,request,value,index,buffer,length,TIMEOUT_MS);
        }
        long ms=SystemClock.uptimeMillis()-started;
        if(n>=0) { a.data=new byte[n]; System.arraycopy(buffer,0,a.data,0,n); }
        else if(ms>=TIMEOUT_MS*3/4) { a.timedOut=true; a.error="not readable: no answer in "+TIMEOUT_MS+" ms"; }
        else a.error="not readable: the camera refused it";
        android.util.Log.i("UsbCameraLab",String.format(Locale.US,"xu probe: %s (0x%02x) bmRequestType 0x%02x wValue 0x%04x wIndex 0x%04x wLength %d -> %s (%d ms)",
                name(request),request,CLASS_INTERFACE_IN,value,index,length,n<0?"failed":n+" bytes "+hex(a.data,32),ms));
        return a;
    }
    static String name(int request) {
        switch(request) {
            case GET_CUR: return "GET_CUR"; case GET_MIN: return "GET_MIN"; case GET_MAX: return "GET_MAX";
            case GET_LEN: return "GET_LEN"; case GET_INFO: return "GET_INFO"; case GET_DEF: return "GET_DEF";
            default: return String.format(Locale.US,"0x%02x",request);
        }
    }
    // "00 1a ff …" with at most `max` bytes, then the total length.
    static String hex(byte[] b,int max) {
        if(b==null) return "";
        if(b.length==0) return "(empty)";
        StringBuilder s=new StringBuilder();
        for(int i=0;i<Math.min(b.length,max);i++) s.append(i==0?"":" ").append(String.format(Locale.US,"%02x",b[i]&0xff));
        if(b.length>max) s.append(" … (").append(b.length).append(" bytes)");
        return s.toString();
    }
    // "get, set, auto-update" from GET_INFO's bits (UVC 1.5 table 4-3).
    static String info(int info) {
        String[] names={"get","set","disabled by an auto mode","auto-update","asynchronous","disabled by the commit state"};
        StringBuilder s=new StringBuilder();
        for(int i=0;i<names.length;i++) if((info>>i&1)!=0) s.append(s.length()==0?"":", ").append(names[i]);
        return s.length()==0?"nothing":s.toString();
    }
}
