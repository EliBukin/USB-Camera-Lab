package com.anxel.usbcameralab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

// A USB camera's raw descriptors as UsbDeviceConnection.getRawDescriptors returns them (the device descriptor, then the
// configuration with its interfaces and endpoints and the class-specific video and audio descriptors), parsed for the Camera
// info page. Pure Java, so a unit test runs it over a captured dump. Every descriptor is walked by its own bLength, a field past
// the end of a short descriptor reads as 0, unknown descriptors are skipped, and a dump that ends early (before the
// configuration's wTotalLength, or inside a descriptor) is parsed up to its last whole descriptor and marked `truncated`. Only the first configuration and the first video function
// are read (`moreConfigurations`, `moreVideoFunctions` say when there is more).
final class UvcDescriptors {
    static final int VIDEO=0x0e, AUDIO=0x01;                                  // interface classes
    static final int CONTROL=1, STREAMING=2;                                  // their subclasses (video and audio alike)
    static final int ISOCHRONOUS=1, BULK=2, INTERRUPT=3;                      // endpoint transfer types
    static final int INPUT_TERMINAL=2, OUTPUT_TERMINAL=3, SELECTOR_UNIT=4, PROCESSING_UNIT=5, EXTENSION_UNIT=6, ENCODING_UNIT=7;
    static final int CAMERA_TERMINAL=0x0201;                                  // wTerminalType of an input terminal that is a camera

    // Device descriptor.
    int usbVersion, deviceClass, deviceSubclass, deviceProtocol, maxPacket0, vendorId, productId, deviceVersion, configurations;
    // The first configuration.
    int totalLength, configAttributes, maxPower, interfaceCount;
    boolean truncated, moreConfigurations, superSpeed;
    int bytes; // the dump's length
    final List<Interface> interfaces=new ArrayList<>(); // every interface descriptor (alternate settings too), in order
    // The video function: the control interface's header, endpoint and units, and the streaming interfaces.
    int uvcVersion=-1, vcInterface=-1, moreVideoFunctions;
    Endpoint vcEndpoint;
    final List<Unit> units=new ArrayList<>();
    final List<Streaming> streaming=new ArrayList<>();
    final List<Audio> audio=new ArrayList<>();

    static final class Interface {
        final int number, alt, interfaceClass, subclass, protocol;
        final List<Endpoint> endpoints=new ArrayList<>();
        Interface(int number,int alt,int interfaceClass,int subclass,int protocol) { this.number=number; this.alt=alt; this.interfaceClass=interfaceClass; this.subclass=subclass; this.protocol=protocol; }
    }
    static final class Endpoint {
        final int address, attributes, maxPacket, interval;
        Endpoint(int address,int attributes,int maxPacket,int interval) { this.address=address; this.attributes=attributes; this.maxPacket=maxPacket; this.interval=interval; }
        int type() { return attributes&3; }
        boolean in() { return (address&0x80)!=0; }
        int packetBytes() { return (maxPacket&0x7ff)*(1+((maxPacket>>11)&3)); } // high-speed "additional transactions" included
        String typeName() { return type()==ISOCHRONOUS?"isochronous":type()==BULK?"bulk":type()==INTERRUPT?"interrupt":"control"; }
        String addressName() { return String.format(Locale.US,"0x%02x",address); }
    }
    // A terminal or unit of the video control interface. `controls` is bmControls as stored (least significant byte first).
    static final class Unit {
        final int kind, id;
        int terminalType, numControls=-1, maxMultiplier;
        String guid; // extension units
        byte[] controls=new byte[0];
        final List<Integer> sources=new ArrayList<>();
        Unit(int kind,int id) { this.kind=kind; this.id=id; }
        boolean has(int bit) { return bit/8<controls.length && (controls[bit/8]>>(bit%8)&1)!=0; }
        List<Integer> bits() { List<Integer> b=new ArrayList<>(); for(int i=0;i<controls.length*8;i++) if(has(i)) b.add(i); return b; }
        String bitmap() { StringBuilder s=new StringBuilder("0x"); for(int i=controls.length-1;i>=0;i--) s.append(String.format(Locale.US,"%02x",controls[i]&0xff)); return controls.length==0?"none":s.toString(); }
        String kindName() {
            switch(kind) {
                case INPUT_TERMINAL: return terminalType==CAMERA_TERMINAL?"Camera terminal":"Input terminal";
                case OUTPUT_TERMINAL: return "Output terminal";
                case SELECTOR_UNIT: return "Selector unit";
                case PROCESSING_UNIT: return "Processing unit";
                case EXTENSION_UNIT: return "Extension unit";
                default: return "Encoding unit";
            }
        }
    }
    // A video streaming interface: its input header (endpoint, still method), formats with their frames, still image sizes.
    static final class Streaming {
        final int number;
        int endpointAddress=-1, terminalLink=-1, stillMethod, numFormats;
        boolean stillFrameDescriptor;
        final List<int[]> stillSizes=new ArrayList<>();
        final List<Format> formats=new ArrayList<>();
        Streaming(int number) { this.number=number; }
    }
    static final class Format {
        final int subtype, index;
        int numFrames, defaultFrame, bitsPerPixel;
        String fourcc; // uncompressed and frame-based formats: the GUID's first four characters
        final List<Frame> frames=new ArrayList<>();
        Format(int subtype,int index) { this.subtype=subtype; this.index=index; }
        // "MJPEG", "YUYV", "NV12", "H.264", …
        String name() {
            switch(subtype) {
                case 0x06: return "MJPEG";
                case 0x04: return fourcc==null?"Uncompressed":"YUY2".equals(fourcc)?"YUYV":fourcc;
                case 0x10: return fourcc==null?"Frame-based":"H264".equals(fourcc)?"H.264":"HEVC".equals(fourcc) || "H265".equals(fourcc)?"H.265":"MJPG".equals(fourcc)?"MJPEG":fourcc;
                case 0x13: return "H.264";
                case 0x15: return "H.264 simulcast";
                case 0x0a: return "MPEG-2 TS";
                case 0x0c: return "DV";
                case 0x12: return "Stream-based";
                case 0x16: return "VP8";
                case 0x18: return "VP8 simulcast";
                default: return String.format(Locale.US,"Format type 0x%02x",subtype);
            }
        }
        // How the format is described: "uncompressed, YUY2, 16 bits per pixel", "frame-based, H264", "UVC 1.5 H.264"
        String kind() {
            switch(subtype) {
                case 0x06: return "compressed";
                case 0x04: return "uncompressed"+(fourcc==null?"":", "+fourcc)+(bitsPerPixel>0?", "+bitsPerPixel+" bits per pixel":"");
                case 0x10: return "frame-based"+(fourcc==null?"":", "+fourcc);
                case 0x13: case 0x15: return "UVC 1.5 H.264";
                default: return "not decoded here";
            }
        }
    }
    static final class Frame {
        int index, width, height, defaultInterval;
        boolean still;                   // bmCapabilities D0: still image capture from this frame (method 1)
        int[] intervals=new int[0];      // discrete frame intervals, 100 ns units
        int minInterval, maxInterval, step; // continuous, when there are no discrete ones
        String size() { return width+" × "+height; }
        // "30, 25, 15 fps" or "5–30 fps (continuous)"
        String rates() {
            if(intervals.length>0) {
                List<Integer> sorted=new ArrayList<>(); for(int i:intervals) if(!sorted.contains(i)) sorted.add(i);
                Collections.sort(sorted);
                StringBuilder s=new StringBuilder();
                for(int i:sorted) s.append(s.length()==0?"":", ").append(fps(i));
                return s+" fps";
            }
            if(maxInterval>0) return fps(maxInterval)+"–"+fps(minInterval)+" fps (continuous)";
            return "no frame rate";
        }
    }
    // An audio interface: its UAC version (from the control interface's header) and, for streaming, the settings that carry sound.
    static final class Audio {
        final int number, subclass;
        int version;
        final List<AudioSetting> settings=new ArrayList<>();
        Audio(int number,int subclass) { this.number=number; this.subclass=subclass; }
    }
    static final class AudioSetting {
        final int alt;
        int channels, bits;
        final List<Integer> rates=new ArrayList<>();
        Endpoint endpoint;
        AudioSetting(int alt) { this.alt=alt; }
        // "2 ch, 24-bit, 48 kHz"
        String describe() {
            StringBuilder s=new StringBuilder();
            if(channels>0) s.append(channels).append(" ch");
            if(bits>0) s.append(s.length()==0?"":", ").append(bits).append("-bit");
            StringBuilder r=new StringBuilder();
            for(int hz:rates) r.append(r.length()==0?"":"/").append(hz%1000==0?String.valueOf(hz/1000):String.format(Locale.US,"%.1f",hz/1000.0));
            if(r.length()>0) s.append(s.length()==0?"":", ").append(r).append(" kHz");
            return s.length()==0?"format not decoded":s.toString();
        }
    }

    // One descriptor of the dump; reads past its end give 0.
    private static final class Desc {
        final byte[] b; final int at, len;
        Desc(byte[] b,int at,int len) { this.b=b; this.at=at; this.len=len; }
        int u8(int off) { return off<len?b[at+off]&0xff:0; }
        int u16(int off) { return u8(off)|u8(off+1)<<8; }
        int u24(int off) { return u16(off)|u8(off+2)<<16; }
        int u32(int off) { return u16(off)|u16(off+2)<<16; }
        boolean has(int off,int n) { return off>=0 && off+n<=len; }
        byte[] bytes(int off,int n) { byte[] r=new byte[Math.max(0,Math.min(n,len-off))]; System.arraycopy(b,at+off,r,0,r.length); return r; }
    }

    static UvcDescriptors parse(byte[] raw) {
        UvcDescriptors u=new UvcDescriptors();
        if(raw==null) return u;
        u.bytes=raw.length;
        int at=0, configStart=-1;
        Interface current=null; Streaming vs=null; Audio audio=null;
        int audioVersion=0;
        boolean device=false;
        while(at+2<=raw.length) {
            int len=raw[at]&0xff, type=raw[at+1]&0xff;
            if(len<2 || at+len>raw.length) { u.truncated=true; break; } // malformed or cut short: not read half
            Desc d=new Desc(raw,at,len);
            if(type==0x01 && !device) {
                device=true;
                u.usbVersion=d.u16(2); u.deviceClass=d.u8(4); u.deviceSubclass=d.u8(5); u.deviceProtocol=d.u8(6); u.maxPacket0=d.u8(7);
                u.vendorId=d.u16(8); u.productId=d.u16(10); u.deviceVersion=d.u16(12); u.configurations=d.u8(17);
            } else if(type==0x02) {
                if(configStart>=0) { u.moreConfigurations=true; break; }
                configStart=at;
                u.totalLength=d.u16(2); u.interfaceCount=d.u8(4); u.configAttributes=d.u8(7); u.maxPower=d.u8(8);
            } else if(type==0x04) {
                current=new Interface(d.u8(2),d.u8(3),d.u8(5),d.u8(6),d.u8(7)); u.interfaces.add(current);
                vs=null; audio=null;
                if(current.interfaceClass==VIDEO && current.subclass==CONTROL) {
                    if(u.vcInterface<0) u.vcInterface=current.number; else if(current.number!=u.vcInterface && current.alt==0) u.moreVideoFunctions++;
                } else if(current.interfaceClass==VIDEO && current.subclass==STREAMING && u.moreVideoFunctions==0) {
                    vs=u.streamingInterface(current.number);
                } else if(current.interfaceClass==AUDIO) {
                    audio=u.audioInterface(current.number,current.subclass);
                    if(current.subclass==STREAMING && current.alt>0) audio.settings.add(new AudioSetting(current.alt));
                }
            } else if(type==0x05) {
                Endpoint e=new Endpoint(d.u8(2),d.u8(3),d.u16(4),d.u8(6));
                if(current!=null) current.endpoints.add(e);
                if(current!=null && current.interfaceClass==VIDEO && current.subclass==CONTROL && current.number==u.vcInterface && u.vcEndpoint==null) u.vcEndpoint=e;
                if(audio!=null && !audio.settings.isEmpty() && current.alt>0) audio.settings.get(audio.settings.size()-1).endpoint=e;
            } else if(type==0x30 || type==0x31) {
                u.superSpeed=true; // SuperSpeed (Plus) endpoint companion: the camera runs at 5 Gbit/s or more
            } else if(type==0x24 && current!=null) {
                int sub=d.u8(2);
                if(current.interfaceClass==VIDEO && current.subclass==CONTROL && current.number==u.vcInterface) u.videoControl(d,sub);
                else if(vs!=null) videoStreaming(vs,d,sub);
                else if(audio!=null && current.subclass==CONTROL && sub==0x01) { audioVersion=d.u16(3); audio.version=audioVersion; }
                else if(audio!=null && current.subclass==STREAMING) { audio.version=audioVersion; audioFormat(audio,d,sub,audioVersion); }
            }
            at+=len;
        }
        if(configStart>=0 && raw.length<configStart+u.totalLength) u.truncated=true;
        return u;
    }
    private Streaming streamingInterface(int number) {
        for(Streaming s:streaming) if(s.number==number) return s;
        Streaming s=new Streaming(number); streaming.add(s); return s;
    }
    private Audio audioInterface(int number,int subclass) {
        for(Audio a:audio) if(a.number==number) return a;
        Audio a=new Audio(number,subclass); audio.add(a); return a;
    }
    // Class-specific video control descriptors (UVC 1.5 §3.7.2): the header and the terminals and units.
    private void videoControl(Desc d,int sub) {
        Unit u;
        switch(sub) {
            case 0x01: uvcVersion=d.u16(3); return;
            case INPUT_TERMINAL:
                u=new Unit(sub,d.u8(3)); u.terminalType=d.u16(4);
                if(u.terminalType==CAMERA_TERMINAL && d.has(14,1)) u.controls=d.bytes(15,d.u8(14));
                break;
            case OUTPUT_TERMINAL:
                u=new Unit(sub,d.u8(3)); u.terminalType=d.u16(4); u.sources.add(d.u8(7));
                break;
            case SELECTOR_UNIT:
                u=new Unit(sub,d.u8(3));
                for(int i=0;i<d.u8(4) && d.has(5+i,1);i++) u.sources.add(d.u8(5+i));
                break;
            case PROCESSING_UNIT:
                u=new Unit(sub,d.u8(3)); u.sources.add(d.u8(4)); u.maxMultiplier=d.u16(5);
                if(d.has(7,1)) u.controls=d.bytes(8,d.u8(7));
                break;
            case EXTENSION_UNIT: {
                u=new Unit(sub,d.u8(3));
                if(d.has(4,16)) u.guid=guid(d.bytes(4,16));
                u.numControls=d.u8(20);
                int pins=d.u8(21);
                for(int i=0;i<pins && d.has(22+i,1);i++) u.sources.add(d.u8(22+i));
                if(d.has(22+pins,1)) u.controls=d.bytes(23+pins,d.u8(22+pins));
                break;
            }
            case ENCODING_UNIT:
                u=new Unit(sub,d.u8(3)); u.sources.add(d.u8(4));
                if(d.has(6,1)) u.controls=d.bytes(7,d.u8(6));
                break;
            default: return;
        }
        units.add(u);
    }
    // Class-specific video streaming descriptors (UVC 1.5 §3.9.2 and the payload specifications).
    private static void videoStreaming(Streaming s,Desc d,int sub) {
        Format last=s.formats.isEmpty()?null:s.formats.get(s.formats.size()-1);
        switch(sub) {
            case 0x01: // input header
                s.numFormats=d.u8(3); s.endpointAddress=d.u8(6); s.terminalLink=d.u8(8); s.stillMethod=d.u8(9);
                return;
            case 0x03: // still image frame
                s.stillFrameDescriptor=true;
                for(int i=0;i<d.u8(4) && d.has(5+4*i,4);i++) s.stillSizes.add(new int[]{d.u16(5+4*i),d.u16(7+4*i)});
                return;
            case 0x04: case 0x10: { // uncompressed, frame-based
                Format f=new Format(sub,d.u8(3)); f.numFrames=d.u8(4); f.fourcc=d.has(5,4)?fourcc(d.bytes(5,4)):null; f.bitsPerPixel=d.u8(21); f.defaultFrame=d.u8(22);
                s.formats.add(f); return;
            }
            case 0x06: { // MJPEG
                Format f=new Format(sub,d.u8(3)); f.numFrames=d.u8(4); f.defaultFrame=d.u8(6);
                s.formats.add(f); return;
            }
            case 0x13: { // UVC 1.5 H.264
                Format f=new Format(sub,d.u8(3)); f.numFrames=d.u8(4); f.defaultFrame=d.u8(5);
                s.formats.add(f); return;
            }
            case 0x0a: case 0x0c: case 0x12: case 0x15: case 0x16: case 0x18:
                s.formats.add(new Format(sub,d.u8(3))); return;
            case 0x05: case 0x07: // uncompressed and MJPEG frames
                if(last!=null) last.frames.add(frame(d,21,25,26));
                return;
            case 0x11: // frame-based frame: no dwMaxVideoFrameBufferSize, dwBytesPerLine before the intervals
                if(last!=null) last.frames.add(frame(d,17,21,26));
                return;
            case 0x14: { // UVC 1.5 H.264 frame
                if(last==null) return;
                Frame f=new Frame(); f.index=d.u8(3); f.width=d.u16(4); f.height=d.u16(6); f.defaultInterval=d.u32(39);
                f.intervals=intervals(d,d.u8(43),44);
                last.frames.add(f);
                return;
            }
            default:
        }
    }
    // The common frame layout: index, bmCapabilities, width, height at 3..8; then the default interval, the interval type and
    // the intervals at the given offsets.
    private static Frame frame(Desc d,int defaultAt,int typeAt,int intervalsAt) {
        Frame f=new Frame(); f.index=d.u8(3); f.still=(d.u8(4)&1)!=0; f.width=d.u16(5); f.height=d.u16(7); f.defaultInterval=d.u32(defaultAt);
        int n=d.u8(typeAt);
        if(n==0) { f.minInterval=d.u32(intervalsAt); f.maxInterval=d.u32(intervalsAt+4); f.step=d.u32(intervalsAt+8); }
        else f.intervals=intervals(d,n,intervalsAt);
        return f;
    }
    private static int[] intervals(Desc d,int n,int at) {
        int count=0; while(count<n && d.has(at+4*count,4)) count++;
        int[] r=new int[count]; for(int i=0;i<count;i++) r[i]=d.u32(at+4*i);
        return r;
    }
    // UAC 1 and 2 format type I: channels, bits and sample rates of a streaming setting.
    private static void audioFormat(Audio a,Desc d,int sub,int version) {
        if(a.settings.isEmpty()) return;
        AudioSetting s=a.settings.get(a.settings.size()-1);
        if(version>=0x0200) {
            if(sub==0x01) s.channels=d.u8(10);   // AS_GENERAL: bNrChannels
            else if(sub==0x02) s.bits=d.u8(5);  // FORMAT_TYPE: bBitResolution (rates live in a clock entity, not read)
            return;
        }
        if(sub!=0x02) return;
        s.channels=d.u8(4); s.bits=d.u8(6);
        int n=d.u8(7);
        if(n==0) { s.rates.add(d.u24(8)); s.rates.add(d.u24(11)); } // continuous: lowest and highest
        else for(int i=0;i<n && d.has(8+3*i,3);i++) s.rates.add(d.u24(8+3*i));
    }

    // ---- What the page shows ----
    // Units whose ID and kind match.
    List<Unit> units(int kind) { List<Unit> r=new ArrayList<>(); for(Unit u:units) if(u.kind==kind) r.add(u); return r; }
    Unit cameraTerminal() { for(Unit u:units) if(u.kind==INPUT_TERMINAL && u.terminalType==CAMERA_TERMINAL) return u; return null; }
    List<Interface> alternates(int number) { List<Interface> r=new ArrayList<>(); for(Interface i:interfaces) if(i.number==number) r.add(i); return r; }
    int maxPowerMa() { return maxPower*(superSpeed?8:2); }
    boolean hasAudio() { return !audio.isEmpty(); }
    // The link speed, from the descriptors of the speed the camera runs at, with the reason; null when they don't tell.
    String speed() {
        if(superSpeed) return "SuperSpeed, 5 Gbit/s or more (it has SuperSpeed endpoint companions)";
        for(Interface i:interfaces) for(Endpoint e:i.endpoints) {
            if(e.type()==BULK && (e.maxPacket&0x7ff)==512) return "high-speed, 480 Mbit/s (its bulk endpoint "+e.addressName()+" takes 512-byte packets)";
            if(e.type()!=BULK && ((e.maxPacket>>11&3)!=0 || (e.maxPacket&0x7ff)>1023)) return "high-speed, 480 Mbit/s (endpoint "+e.addressName()+" uses high-bandwidth packets)";
        }
        for(Interface i:interfaces) for(Endpoint e:i.endpoints) if(e.type()==BULK && (e.maxPacket&0x7ff)<=64) return "full-speed, 12 Mbit/s (its bulk endpoint "+e.addressName()+" takes "+(e.maxPacket&0x7ff)+"-byte packets)";
        return null;
    }

    static String bcd(int v) { return String.format(Locale.US,"%x.%02x",v>>8,v&0xff); }
    static String hex4(int v) { return String.format(Locale.US,"%04x",v); }
    // Frames per second from a frame interval in 100 ns units: "30", "7.5", "29.97".
    static String fps(int interval) {
        if(interval<=0) return "?";
        String s=String.format(Locale.US,"%.2f",1e7/interval);
        return s.contains(".")?s.replaceAll("0+$","").replaceAll("\\.$",""):s;
    }
    // A GUID as stored in USB descriptors (the first three fields little-endian): {dd880f8a-1cba-4954-8a25-f7875967f0f7}.
    static String guid(byte[] g) {
        int[] o={3,2,1,0,-1,5,4,-1,7,6,-1,8,9,-1,10,11,12,13,14,15};
        StringBuilder s=new StringBuilder("{");
        for(int i:o) if(i<0) s.append('-'); else s.append(String.format(Locale.US,"%02x",i<g.length?g[i]&0xff:0));
        return s.append('}').toString();
    }
    private static String fourcc(byte[] b) {
        StringBuilder s=new StringBuilder();
        for(byte c:b) { int v=c&0xff; if(v<0x20 || v>0x7e) return null; s.append((char)v); }
        return s.toString().trim();
    }
    static String terminalName(int type) {
        switch(type) {
            case 0x0100: return "vendor-specific";
            case 0x0101: return "USB streaming";
            case 0x0201: return "camera";
            case 0x0202: return "media transport input";
            case 0x0301: return "display";
            case 0x0302: return "media transport output";
            case 0x0401: return "composite video connector";
            case 0x0402: return "S-Video connector";
            case 0x0403: return "component video connector";
            default: return String.format(Locale.US,"type 0x%04x",type);
        }
    }
    // The UVC names of the camera terminal's and processing unit's bmControls bits (UVC 1.5 tables 3-6 and 3-8).
    private static final String[] CT_BITS={"Scanning Mode","Auto-Exposure Mode","Auto-Exposure Priority","Exposure Time (Absolute)","Exposure Time (Relative)",
        "Focus (Absolute)","Focus (Relative)","Iris (Absolute)","Iris (Relative)","Zoom (Absolute)","Zoom (Relative)","PanTilt (Absolute)","PanTilt (Relative)",
        "Roll (Absolute)","Roll (Relative)",null,null,"Focus, Auto","Privacy","Focus, Simple","Window","Region of Interest"};
    private static final String[] PU_BITS={"Brightness","Contrast","Hue","Saturation","Sharpness","Gamma","White Balance Temperature","White Balance Component",
        "Backlight Compensation","Gain","Power Line Frequency","Hue, Auto","White Balance Temperature, Auto","White Balance Component, Auto","Digital Multiplier",
        "Digital Multiplier Limit","Analog Video Standard","Analog Video Lock Status","Contrast, Auto"};
    static String cameraControl(int bit) { return bit<CT_BITS.length && CT_BITS[bit]!=null?CT_BITS[bit]:"Reserved bit "+bit; }
    static String processingControl(int bit) { return bit<PU_BITS.length?PU_BITS[bit]:"Reserved bit "+bit; }

    // ---- Vendor names ----
    // A short table of camera, webcam chip and phone makers, named as in usb.ids. Android has no vendor list of its own; for the
    // others the page says whether the ID is in the public list at all (`publicIds`: the IDs from usb.ids, see the asset).
    private static final int[] VENDOR_IDS={0x03f0,0x041e,0x0408,0x045e,0x046d,0x0458,0x0471,0x04a9,0x04b4,0x04ca,0x04e8,0x04f2,0x054c,0x05a3,0x05ac,0x05e3,0x093a,
        0x0ac8,0x0bda,0x0c45,0x0fd9,0x12d1,0x13d3,0x1415,0x1532,0x174f,0x17ef,0x1871,0x18d1,0x18ec,0x1b3f,0x1bcf,0x1d6b,0x1e4e,0x1edb,0x2232,0x2560,0x2717,0x413c,0x5986,0x8086};
    private static final String[] VENDOR_NAMES={"HP","Creative Technology","Quanta Computer","Microsoft","Logitech","KYE Systems (Genius)","Philips (or NXP)","Canon",
        "Cypress Semiconductor","Lite-On Technology","Samsung Electronics","Chicony Electronics","Sony","ARC International","Apple","Genesys Logic","Pixart Imaging",
        "Z-Star Microelectronics","Realtek Semiconductor","Microdia","Elgato Systems","Huawei Technologies","IMC Networks","Nam Tai E&E Products or OmniVision Technologies",
        "Razer USA","Syntek","Lenovo","Aveo Technology","Google","Arkmicro Technologies","Generalplus Technology","Sunplus Innovation Technology","Linux Foundation",
        "Cubeternet","Blackmagic design","Silicon Motion","e-con Systems","Xiaomi","Dell Computer","Bison Electronics","Intel"};
    // "Logitech", "in the public USB ID list, not in this app's short table", "not in the public USB ID list"
    static String vendor(int id,String publicIds) {
        for(int i=0;i<VENDOR_IDS.length;i++) if(VENDOR_IDS[i]==id) return VENDOR_NAMES[i];
        if(publicIds==null) return "not in this app's short vendor table";
        for(String line:publicIds.split("\n")) if(!line.startsWith("#") && (" "+line+" ").contains(" "+hex4(id)+" ")) return "in the public USB ID list, not in this app's short table";
        return "not in the public USB ID list";
    }
}
