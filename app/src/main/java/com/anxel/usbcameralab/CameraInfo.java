package com.anxel.usbcameralab;

import static com.anxel.usbcameralab.UvcDescriptors.*;

import android.hardware.usb.UsbDevice;
import android.os.Build;
import com.anxel.usbcameralab.CameraSession.State;
import com.serenegiant.usb.Size;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// The Camera info report: what the plugged-in camera is and offers, from its USB descriptors (UvcDescriptors), Android's view
// of it (UsbDevice, the audio inputs), what the controls panel made of its controls, and the extension unit probe (XuProbe).
// The page shows the sections as cards; Copy as text writes the same rows as plain text, for bug reports. Main thread.
final class CameraInfo {
    static final int PLAIN=0, GOOD=1, MUTED=2, WARN=3;
    static final class Row {
        final String label, value, detail; final int tone; final boolean mono;
        Row(String label,String value,String detail,int tone,boolean mono) { this.label=label; this.value=value; this.detail=detail; this.tone=tone; this.mono=mono; }
    }
    static final class Section {
        final String title; String summary;
        final List<Row> rows=new ArrayList<>();
        final List<String> notes=new ArrayList<>();
        Section(String title) { this.title=title; }
        Row add(String label,String value) { return add(label,value,null,PLAIN); }
        Row add(String label,String value,String detail,int tone) { Row r=new Row(label,value,detail,tone,false); rows.add(r); return r; }
        void mono(String label,String value,String detail) { rows.add(new Row(label,value,detail,PLAIN,true)); }
    }

    // What the page gathered.
    UsbDevice device;
    boolean permission, reading; // reading: the descriptors are on their way
    String serial, rawError, publicIds, appVersion, probeState;
    UvcDescriptors desc;
    final List<String> audioInputs=new ArrayList<>();
    State state=State.IDLE;
    Size mode;
    ControlsPanel controls;
    final List<XuProbe.Result> probe=new ArrayList<>();
    Date readAt=new Date();

    List<Section> sections() {
        List<Section> out=new ArrayList<>();
        if(device==null) {
            Section s=new Section("No camera");
            s.notes.add("No USB camera is connected. Plug one in through the USB-C adapter, and this page shows what it is and offers.");
            out.add(s); out.add(about());
            return out;
        }
        out.add(camera());
        if(desc!=null) {
            out.add(video()); out.add(controls());
            for(Streaming s:desc.streaming) { out.add(streaming(s)); for(Format f:s.formats) out.add(format(f)); }
            out.add(audio()); out.add(extensionUnits());
        } else out.add(audio());
        out.add(about());
        return out;
    }

    private Section camera() {
        Section s=new Section("Camera");
        s.add("Name",or(device.getProductName()));
        s.add("Manufacturer",or(device.getManufacturerName()));
        s.add("Serial number",serial==null?(permission?"none":"needs USB access"):serial);
        s.mono("VID:PID",hex4(device.getVendorId())+":"+hex4(device.getProductId()),null);
        s.add("Vendor",vendor(device.getVendorId(),publicIds),"by vendor ID, against usb.ids of 2024-03-18; the manufacturer above is the camera's own name for itself",PLAIN);
        if(desc!=null) {
            s.add("Firmware version",bcd(desc.deviceVersion),"bcdDevice",PLAIN);
            s.add("USB version",bcd(desc.usbVersion),"bcdUSB",PLAIN);
            String speed=desc.speed();
            if(speed!=null) { int cut=speed.indexOf(" ("); s.add("Speed",cut<0?speed:speed.substring(0,cut),cut<0?null:"inferred: "+speed.substring(cut+2,speed.length()-1),PLAIN); }
            s.add("Power",desc.maxPowerMa()+" mA maximum, "+((desc.configAttributes&0x40)!=0?"self-powered":"bus-powered"),"bMaxPower",PLAIN);
        }
        s.add("Interfaces",interfaces());
        s.mono("Android device",device.getDeviceName(),null);
        if(desc!=null) {
            s.add("Descriptors",desc.bytes+" bytes"+(desc.truncated?", cut short":""),desc.truncated?"the configuration says "+desc.totalLength+" bytes; this report reads what Android gave":null,desc.truncated?WARN:PLAIN);
            if(desc.moreConfigurations || desc.configurations>1) s.notes.add("The camera has "+desc.configurations+" configurations; this report reads the first.");
        } else s.notes.add(reading?"Reading the descriptors…":rawError!=null?rawError:"The descriptors couldn't be read, so only what Android lists is shown.");
        return s;
    }
    // "4: video control, video streaming, audio control, audio streaming", from Android's own parse of the configuration.
    private String interfaces() {
        StringBuilder s=new StringBuilder();
        List<Integer> seen=new ArrayList<>();
        for(int i=0;i<device.getInterfaceCount();i++) {
            android.hardware.usb.UsbInterface f=device.getInterface(i);
            if(seen.contains(f.getId())) continue;
            seen.add(f.getId());
            s.append(s.length()==0?"":", ").append(className(f.getInterfaceClass(),f.getInterfaceSubclass()));
        }
        return seen.size()+(seen.isEmpty()?"":": "+s);
    }
    private static String className(int c,int sub) {
        if(c==VIDEO) return sub==CONTROL?"video control":sub==STREAMING?"video streaming":"video";
        if(c==AUDIO) return sub==CONTROL?"audio control":sub==STREAMING?"audio streaming":sub==3?"MIDI":"audio";
        if(c==3) return "HID";
        if(c==0xff) return "vendor-specific";
        return String.format(Locale.US,"class 0x%02x",c);
    }

    private Section video() {
        Section s=new Section("USB video (UVC)");
        if(desc.uvcVersion<0) { s.notes.add("No UVC video control interface in the descriptors."); return s; }
        s.add("UVC version",bcd(desc.uvcVersion));
        s.add("Driver","this app, through "+XuProbe.LIBRARY,"libuvc over libusb on Android's USB host API, not Android's camera service",PLAIN);
        Endpoint e=desc.vcEndpoint;
        s.add("Video control","interface "+desc.vcInterface+(e==null?", no status endpoint":", "+e.typeName()+" endpoint "+e.addressName()+" ("+e.packetBytes()+" bytes)"));
        String path=path();
        if(path!=null) s.add("Picture path",path,"from the streaming output terminal back to its source",PLAIN);
        List<Unit> onPath=pathUnits();
        for(Unit u:desc.units) {
            String from=u.sources.isEmpty()?"":"from "+join(u.sources);
            String value;
            switch(u.kind) {
                case INPUT_TERMINAL: value=terminalName(u.terminalType); break;
                case OUTPUT_TERMINAL: value=terminalName(u.terminalType)+", "+from; break;
                case PROCESSING_UNIT: value=from+(u.maxMultiplier>0?String.format(Locale.US,", digital zoom up to ×%.2f",u.maxMultiplier/100.0):""); break;
                case EXTENSION_UNIT: value=from+", vendor-defined"; break;
                default: value=from;
            }
            boolean side=path!=null && !onPath.contains(u) && u.kind!=OUTPUT_TERMINAL;
            s.add(u.kindName()+" "+u.id,value,side?"a side branch: nothing takes its output":null,PLAIN);
        }
        if(desc.moreVideoFunctions>0) s.notes.add("The camera has more than one video function; this report reads the first.");
        return s;
    }
    // The units the picture passes through: from the USB streaming output terminal back along each first source.
    private List<Unit> pathUnits() {
        List<Unit> chain=new ArrayList<>();
        Unit u=null;
        for(Unit o:desc.units(OUTPUT_TERMINAL)) if(o.terminalType==0x0101) { u=o; break; }
        while(u!=null && chain.size()<16 && !chain.contains(u)) { chain.add(0,u); u=u.sources.isEmpty()?null:unit(u.sources.get(0)); }
        return chain;
    }
    // "camera 1 → processing 7 → output 2"
    private String path() {
        List<Unit> chain=pathUnits(); if(chain.size()<2) return null;
        StringBuilder s=new StringBuilder();
        for(Unit u:chain) s.append(s.length()==0?"":" → ").append(u.kindName().toLowerCase(Locale.US).replace(" terminal","").replace(" unit","")).append(' ').append(u.id);
        return s.toString();
    }
    private Unit unit(int id) { for(Unit u:desc.units) if(u.id==id) return u; return null; }

    // Every control bit the camera terminal and processing units declare, and what the controls panel does with it.
    private Section controls() {
        Section s=new Section("Controls");
        int declared=0, shown=0, notHere=0, noRange=0, other=0;
        List<Unit> owners=new ArrayList<>();
        Unit ct=desc.cameraTerminal(); if(ct!=null) owners.add(ct);
        owners.addAll(desc.units(PROCESSING_UNIT));
        for(Unit u:owners) for(int bit:u.bits()) {
            boolean camera=u.kind==INPUT_TERMINAL;
            String uvc=camera?cameraControl(bit):processingControl(bit);
            int flag=bit>30?0:camera?1<<bit:0x80000000|1<<bit; // UVCControl's flags: camera terminal bits, and processing unit bits with the top bit set
            String label=flag==0?null:ControlsPanel.label(flag);
            int status=flag==0?ControlsPanel.NOT_IN_APP:controls==null?ControlsPanel.WHEN_OPEN:controls.status(flag);
            String value; int tone;
            switch(status) {
                case ControlsPanel.SHOWN: value="Shown"; tone=GOOD; shown++; break;
                case ControlsPanel.NO_RANGE: value="No usable range"; tone=WARN; noRange++; break;
                case ControlsPanel.NOT_ADJUSTABLE: value="Not adjustable here"; tone=MUTED; notHere++; break;
                case ControlsPanel.NOT_REPORTED: value="Not reported by UVCAndroid"; tone=WARN; other++; break;
                case ControlsPanel.WHEN_OPEN: value="Shown when open"; tone=MUTED; break;
                default: value="Not in this app"; tone=MUTED; other++;
            }
            declared++;
            s.add(label==null?uvc:label,value,"UVC "+uvc+", "+(camera?"camera terminal":"processing unit")+" "+u.id+" bit "+bit,tone);
        }
        StringBuilder sum=new StringBuilder(declared+" declared");
        if(controls!=null && controls.ready()) {
            sum.append(" · ").append(shown).append(" shown");
            if(notHere>0) sum.append(" · ").append(notHere).append(" not adjustable here");
            if(noRange>0) sum.append(" · ").append(noRange).append(" without a usable range");
            if(other>0) sum.append(" · ").append(other).append(" not in this app");
        }
        s.summary=sum.toString();
        if(declared==0) s.notes.add("The camera declares no standard controls.");
        s.notes.add("Declared: the bits set in bmControls. Shown: a row in the controls panel. Relative, packed and analog controls have no settings there.");
        if(controls==null || !controls.ready()) s.notes.add("Which ones are shown is known once the camera is open.");
        return s;
    }

    private Section streaming(Streaming vs) {
        Section s=new Section(desc.streaming.size()>1?"Streaming interface "+vs.number:"Streaming");
        s.add("Interface",vs.number+(vs.terminalLink>=0?", fed by output terminal "+vs.terminalLink:""));
        List<Interface> alts=desc.alternates(vs.number);
        List<Endpoint> iso=new ArrayList<>(); List<Integer> isoAlts=new ArrayList<>(); Endpoint bulk=null;
        for(Interface i:alts) for(Endpoint e:i.endpoints) {
            if(e.type()==BULK && bulk==null) bulk=e;
            else if(e.type()==ISOCHRONOUS) { iso.add(e); isoAlts.add(i.alt); }
        }
        if(bulk!=null) s.add("Endpoint","bulk "+bulk.addressName()+(bulk.in()?" IN":" OUT")+", "+bulk.packetBytes()+"-byte packets");
        if(!iso.isEmpty()) {
            int lo=Integer.MAX_VALUE, hi=0; for(Endpoint e:iso) { lo=Math.min(lo,e.packetBytes()); hi=Math.max(hi,e.packetBytes()); }
            s.add("Endpoint","isochronous "+iso.get(0).addressName()+", "+iso.size()+" bandwidth setting"+(iso.size()==1?"":"s")+" (alt "+join(isoAlts)+"): "+(lo==hi?lo:lo+"–"+hi)+" bytes per interval");
        }
        if(bulk==null && iso.isEmpty()) s.add("Endpoint","none found",null,WARN);
        int stillFrames=0, frames=0;
        for(Format f:vs.formats) for(Frame fr:f.frames) { frames++; if(fr.still) stillFrames++; }
        String still;
        if(vs.stillMethod==0 && !vs.stillFrameDescriptor && stillFrames==0) still="not supported";
        else {
            String[] methods={"none","method 1: a frame from the video stream","method 2: the stream switches to a still size","method 3: a separate still endpoint"};
            still=vs.stillMethod<methods.length?methods[vs.stillMethod]:"method "+vs.stillMethod;
            if(!vs.stillSizes.isEmpty()) { StringBuilder z=new StringBuilder(); for(int[] wh:vs.stillSizes) z.append(z.length()==0?"":", ").append(wh[0]).append(" × ").append(wh[1]); still+="; still sizes "+z; }
            if(stillFrames>0) still+="; "+stillFrames+" of "+frames+" frame sizes allow a still";
        }
        s.add("Still images",still,"bStillCaptureMethod "+vs.stillMethod+", "+(vs.stillFrameDescriptor?"a":"no")+" still image frame descriptor",PLAIN);
        StringBuilder f=new StringBuilder();
        for(Format fm:vs.formats) f.append(f.length()==0?"":", ").append(fm.name()).append(" (").append(fm.frames.size()).append(fm.frames.size()==1?" size)":" sizes)");
        s.add("Formats",f.length()==0?"none":f.toString());
        if(vs.numFormats!=vs.formats.size()) s.notes.add("The input header announces "+vs.numFormats+" formats; "+vs.formats.size()+" were found.");
        return s;
    }
    private static Section format(Format f) {
        Section s=new Section(f.name());
        s.summary="format "+f.index+" · "+f.kind()+" · "+f.frames.size()+(f.frames.size()==1?" frame size":" frame sizes");
        for(int i=0;i<f.frames.size();i++) {
            Frame fr=f.frames.get(i);
            List<String> d=new ArrayList<>();
            if(fr.index==f.defaultFrame) d.add("default");
            if(fr.still) d.add("still");
            for(int j=0;j<i;j++) if(f.frames.get(j).width==fr.width && f.frames.get(j).height==fr.height) { d.add("listed twice"); break; }
            s.add(fr.size(),fr.rates(),d.isEmpty()?null:String.join(", ",d),PLAIN);
        }
        if(f.frames.isEmpty()) s.notes.add("This app doesn't decode this format's frame descriptors.");
        else if(f.numFrames!=f.frames.size()) s.notes.add("The format announces "+f.numFrames+" frame sizes; "+f.frames.size()+" were found.");
        return s;
    }

    private Section audio() {
        Section s=new Section("Audio");
        boolean usbAudio;
        if(desc!=null) {
            usbAudio=desc.hasAudio();
            int version=0; List<Integer> control=new ArrayList<>(), stream=new ArrayList<>();
            for(Audio a:desc.audio) { if(a.version>0) version=a.version; (a.subclass==CONTROL?control:stream).add(a.number); }
            s.add("USB audio",usbAudio?"yes, USB Audio Class "+(version>=0x0200?"2":"1"):"none",usbAudio?"bcdADC "+bcd(version):null,PLAIN);
            if(usbAudio) s.add("Interfaces",(control.isEmpty()?"":"control "+join(control))+(stream.isEmpty()?"":(control.isEmpty()?"":", ")+"streaming "+join(stream)));
            for(Audio a:desc.audio) for(AudioSetting st:a.settings) {
                Endpoint e=st.endpoint;
                s.add("Interface "+a.number+", alt "+st.alt,st.describe(),e==null?null:e.typeName()+" "+e.addressName()+(e.in()?" IN":" OUT")+", "+e.packetBytes()+" bytes",PLAIN);
            }
        } else {
            usbAudio=false;
            for(int i=0;i<device.getInterfaceCount();i++) if(device.getInterface(i).getInterfaceClass()==AUDIO) usbAudio=true;
            s.add("USB audio",usbAudio?"yes":"none");
        }
        if(audioInputs.isEmpty()) s.add("Android input","none listed",usbAudio?"Android lists no USB audio input":null,usbAudio?WARN:MUTED);
        else for(String name:audioInputs) s.add("Android input",name,"a USB input in Android's audio device list",PLAIN);
        return s;
    }

    private Section extensionUnits() {
        Section s=new Section("Extension units");
        List<Unit> xus=desc.units(EXTENSION_UNIT);
        s.summary=xus.isEmpty()?"none":xus.size()+" · read only";
        s.notes.add("Read only: this page sends the camera only GET requests (GET_INFO, GET_LEN, GET_CUR, GET_MIN, GET_MAX, GET_DEF), never a SET, so nothing on the camera changes.");
        if(xus.isEmpty()) { s.add("Extension units","none declared"); return s; }
        for(Unit xu:xus) {
            s.add("Unit "+xu.id,"from "+join(xu.sources)+", vendor-defined","what its controls do isn't in the UVC standard",PLAIN);
            s.mono("GUID",xu.guid==null?"?":xu.guid,null);
            List<Integer> bits=xu.bits();
            s.add("bNumControls",String.valueOf(xu.numControls),bits.size()!=xu.numControls?"but bmControls sets "+bits.size()+" bit"+(bits.size()==1?"":"s")+"; the probe reads those":null,bits.size()!=xu.numControls?WARN:PLAIN);
            s.mono("bmControls",xu.bitmap()+(bits.isEmpty()?"":": selectors "+selectorList(bits)),xu.controls.length+"-byte bitmap; bit n is selector n+1");
            XuProbe.Result r=null; for(XuProbe.Result p:probe) if(p.unit==xu.id) r=p;
            if(r==null) { s.add("Probe",probeState==null?"not run":probeState,null,MUTED); continue; }
            int readable=0, echoes=0;
            for(XuProbe.Selector sel:r.selectors) {
                if(sel.cur!=null) readable++;
                Echo echo=new Echo(sel,xu.id,desc.vcInterface);
                if(echo.any) echoes++;
                s.mono("Selector "+sel.selector,selector(sel,echo),sel.fromBits?"bit "+(sel.selector-1):"from bNumControls");
            }
            s.add("Probe",r.selectors.size()+" selector"+(r.selectors.size()==1?"":"s")+" read, "+readable+" with a current value; "+r.requests+" GET requests, no SET",null,r.stopped==null?PLAIN:WARN);
            if(echoes>0) s.notes.add("Unit "+xu.id+": in "+echoes+" of "+r.selectors.size()+" selectors, answers marked (echo) carry the request's own setup bytes (bmRequestType a1, the request code, wValue, wIndex, wLength). The firmware seems to hand back its request buffer there, so those bytes are probably not settings.");
            if(r.stopped!=null) s.notes.add("Unit "+xu.id+": "+r.stopped+".");
        }
        return s;
    }
    // "selectors 1–7" or "selectors 1, 3, 5"
    private static String selectorList(List<Integer> bits) {
        boolean run=true; for(int i=1;i<bits.size();i++) if(bits.get(i)!=bits.get(i-1)+1) run=false;
        if(run && bits.size()>2) return (bits.get(0)+1)+"–"+(bits.get(bits.size()-1)+1);
        StringBuilder s=new StringBuilder(); for(int b:bits) s.append(s.length()==0?"":", ").append(b+1); return s.toString();
    }
    // GET_INFO 0x03 (get, set)
    // GET_LEN  4 bytes
    // GET_CUR  01 00 00 00 = 1
    private static String selector(XuProbe.Selector s,Echo e) {
        StringBuilder b=new StringBuilder();
        b.append("GET_INFO ").append(s.info<0?s.infoError:String.format(Locale.US,"0x%02x (%s)",s.info,XuProbe.info(s.info)));
        b.append("\nGET_LEN  ").append(s.length<0?s.lengthError:s.length+(s.length==1?" byte":" bytes"));
        b.append("\nGET_CUR  ").append(s.cur!=null?value(s.cur,e.cur):s.curError);
        if(s.min!=null || s.max!=null || s.def!=null) {
            b.append("\nGET_MIN  ").append(s.min!=null?value(s.min,e.min):"not readable");
            b.append("\nGET_MAX  ").append(s.max!=null?value(s.max,e.max):"not readable");
            b.append("\nGET_DEF  ").append(s.def!=null?value(s.def,e.def):"not readable");
        } else if(s.rangeError!=null) b.append("\nMIN/MAX/DEF ").append(s.rangeError);
        return b.toString();
    }
    // Which answers carry the request's own setup packet: a1, the request code and wValue at the start, or wIndex and wLength
    // at bytes 4..7 (the XC-TECH camera's unit 8 does both).
    private static final class Echo {
        final boolean cur, min, max, def, any;
        Echo(XuProbe.Selector s,int unit,int vcInterface) {
            cur=is(s.cur,XuProbe.GET_CUR,s,unit,vcInterface); min=is(s.min,XuProbe.GET_MIN,s,unit,vcInterface);
            max=is(s.max,XuProbe.GET_MAX,s,unit,vcInterface); def=is(s.def,XuProbe.GET_DEF,s,unit,vcInterface);
            any=cur || min || max || def;
        }
        private static boolean is(byte[] v,int request,XuProbe.Selector s,int unit,int vcInterface) {
            if(v==null) return false;
            boolean head=v.length>=4 && (v[0]&0xff)==0xa1 && (v[1]&0xff)==request && v[2]==0 && (v[3]&0xff)==s.selector;
            boolean tail=v.length>=8 && (v[4]&0xff)==vcInterface && (v[5]&0xff)==unit && (v[6]&0xff)==(s.length&0xff) && (v[7]&0xff)==(s.length>>8&0xff);
            return head || tail;
        }
    }
    // Hex, truncated; an echo is marked, other 1, 2 and 4-byte values also read as a little-endian number.
    private static String value(byte[] v,boolean echo) {
        String hex=XuProbe.hex(v,16);
        if(echo) return hex+" (echo)";
        if(v.length!=1 && v.length!=2 && v.length!=4) return hex;
        long n=0; for(int i=v.length-1;i>=0;i--) n=n<<8|(v[i]&0xff);
        return hex+" = "+n;
    }

    private Section about() {
        Section s=new Section("This report");
        s.add("App","USB Camera Lab "+or(appVersion)+" · "+XuProbe.LIBRARY);
        s.add("Phone",Build.MANUFACTURER+" "+Build.MODEL+", Android "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+")");
        String camera;
        switch(state) {
            case OPEN: camera="open"+(mode==null?"":", streaming "+CameraSession.modeName(mode)); break;
            case CONNECTING: camera="connecting"; break;
            case CLOSING: camera="closing"; break;
            default: camera="not open";
        }
        s.add("Camera",camera);
        s.add("Read",new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(readAt));
        return s;
    }

    // The whole report as plain text.
    String text() {
        StringBuilder t=new StringBuilder("USB Camera Lab · Camera info\n");
        for(Section s:sections()) {
            t.append('\n').append(s.title.toUpperCase(Locale.US)).append(s.summary==null?"":" · "+s.summary).append('\n');
            for(Row r:s.rows) {
                t.append(r.label).append(": ").append(r.value.replace("\n","\n    "));
                if(r.detail!=null) t.append(r.value.contains("\n")?"\n    (":" (").append(r.detail).append(')');
                t.append('\n');
            }
            for(String n:s.notes) t.append("Note: ").append(n).append('\n');
        }
        return t.toString();
    }

    private static String or(String s) { return s==null || s.isEmpty()?"—":s; }
    private static String join(List<Integer> l) { StringBuilder s=new StringBuilder(); for(int i:l) s.append(s.length()==0?"":", ").append(i); return s.toString(); }
}
