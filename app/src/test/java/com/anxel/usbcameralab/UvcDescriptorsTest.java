package com.anxel.usbcameralab;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

// UvcDescriptors over the XC-TECH 4K camera's descriptors (/sys/bus/usb/devices/1-1/descriptors on the A20, the same bytes
// getRawDescriptors returns), a hand-made H.264 camera with an isochronous endpoint, a cut-short dump and random bytes.
public class UvcDescriptorsTest {
    private static byte[] xcTech() throws IOException {
        try(InputStream in=UvcDescriptorsTest.class.getResourceAsStream("/xc_tech_4k.bin")) {
            assertNotNull(in);
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[4096]; int n;
            while((n=in.read(b))>0) out.write(b,0,n);
            return out.toByteArray();
        }
    }

    @Test public void device() throws IOException {
        UvcDescriptors u=UvcDescriptors.parse(xcTech());
        assertEquals(1013,u.bytes);
        assertFalse(u.truncated); assertFalse(u.moreConfigurations);
        assertEquals("5843",UvcDescriptors.hex4(u.vendorId)); assertEquals("7884",UvcDescriptors.hex4(u.productId));
        assertEquals("1.00",UvcDescriptors.bcd(u.deviceVersion));
        assertEquals("2.00",UvcDescriptors.bcd(u.usbVersion));
        assertEquals(256,u.maxPowerMa());
        assertEquals(0x80,u.configAttributes); // bus-powered
        assertEquals(4,u.interfaceCount);
        assertTrue(u.speed(),u.speed().startsWith("high-speed, 480 Mbit/s"));
    }

    @Test public void videoControl() throws IOException {
        UvcDescriptors u=UvcDescriptors.parse(xcTech());
        assertEquals(0x0100,u.uvcVersion);
        assertEquals(0,u.vcInterface); assertEquals(0,u.moreVideoFunctions);
        assertEquals(0x82,u.vcEndpoint.address); assertEquals(64,u.vcEndpoint.maxPacket); assertEquals(UvcDescriptors.INTERRUPT,u.vcEndpoint.type());
        assertEquals(4,u.units.size());
        UvcDescriptors.Unit ct=u.cameraTerminal();
        assertEquals(1,ct.id);
        assertEquals(Arrays.asList(1,3,5),ct.bits());
        assertEquals("Auto-Exposure Mode",UvcDescriptors.cameraControl(1));
        assertEquals("Exposure Time (Absolute)",UvcDescriptors.cameraControl(3));
        assertEquals("Focus (Absolute)",UvcDescriptors.cameraControl(5));
        UvcDescriptors.Unit pu=u.units(UvcDescriptors.PROCESSING_UNIT).get(0);
        assertEquals(7,pu.id); assertEquals(Arrays.asList(1),pu.sources);
        assertEquals(Arrays.asList(0,1,3,4,5,6,8,9,10,12),pu.bits());
        assertEquals("0x177b",pu.bitmap());
        assertEquals(13,ct.bits().size()+pu.bits().size());
        UvcDescriptors.Unit xu=u.units(UvcDescriptors.EXTENSION_UNIT).get(0);
        assertEquals(8,xu.id);
        assertEquals("{dd880f8a-1cba-4954-8a25-f7875967f0f7}",xu.guid);
        assertEquals(0,xu.numControls);
        assertEquals(Arrays.asList(0,1,2,3,4,5,6),xu.bits());
        assertEquals(Arrays.asList(7),xu.sources);
        UvcDescriptors.Unit ot=u.units(UvcDescriptors.OUTPUT_TERMINAL).get(0);
        assertEquals(2,ot.id); assertEquals(0x0101,ot.terminalType); assertEquals(Arrays.asList(7),ot.sources);
    }

    @Test public void streaming() throws IOException {
        UvcDescriptors u=UvcDescriptors.parse(xcTech());
        assertEquals(1,u.streaming.size());
        UvcDescriptors.Streaming s=u.streaming.get(0);
        assertEquals(1,s.number); assertEquals(0x83,s.endpointAddress); assertEquals(2,s.terminalLink);
        assertEquals(0,s.stillMethod); assertFalse(s.stillFrameDescriptor);
        List<UvcDescriptors.Interface> alts=u.alternates(1);
        assertEquals(1,alts.size());
        UvcDescriptors.Endpoint e=alts.get(0).endpoints.get(0);
        assertEquals(UvcDescriptors.BULK,e.type()); assertEquals(512,e.packetBytes()); assertEquals("0x83",e.addressName());
        assertEquals(2,s.formats.size());
        UvcDescriptors.Format mjpeg=s.formats.get(0), yuyv=s.formats.get(1);
        assertEquals("MJPEG",mjpeg.name()); assertEquals(11,mjpeg.frames.size()); assertEquals(11,mjpeg.numFrames);
        assertEquals("3840 × 2160",mjpeg.frames.get(0).size()); assertEquals("30 fps",mjpeg.frames.get(0).rates());
        assertEquals("2560 × 1440",mjpeg.frames.get(10).size());
        assertEquals(3,mjpeg.defaultFrame);
        assertEquals("YUYV",yuyv.name()); assertEquals("YUY2",yuyv.fourcc); assertEquals(16,yuyv.bitsPerPixel); assertEquals(9,yuyv.frames.size());
        assertEquals("2592 × 1944",yuyv.frames.get(0).size()); assertEquals("2 fps",yuyv.frames.get(0).rates());
        assertEquals("1920 × 1080",yuyv.frames.get(2).size()); assertEquals("5 fps",yuyv.frames.get(2).rates());
        assertEquals("20 fps",yuyv.frames.get(5).rates());
        for(UvcDescriptors.Format f:s.formats) for(UvcDescriptors.Frame fr:f.frames) assertFalse(fr.still);
    }

    @Test public void audio() throws IOException {
        UvcDescriptors u=UvcDescriptors.parse(xcTech());
        assertTrue(u.hasAudio());
        assertEquals(2,u.audio.size());
        UvcDescriptors.Audio control=u.audio.get(0), stream=u.audio.get(1);
        assertEquals(2,control.number); assertEquals(UvcDescriptors.CONTROL,control.subclass); assertEquals(0x0100,control.version);
        assertEquals(3,stream.number); assertEquals(UvcDescriptors.STREAMING,stream.subclass);
        assertEquals(3,stream.settings.size());
        assertEquals("2 ch, 24-bit, 44.1 kHz",stream.settings.get(0).describe());
        assertEquals("2 ch, 24-bit, 48 kHz",stream.settings.get(1).describe());
        assertEquals("2 ch, 24-bit, 96 kHz",stream.settings.get(2).describe());
        assertEquals(0x86,stream.settings.get(0).endpoint.address);
        assertEquals(UvcDescriptors.ISOCHRONOUS,stream.settings.get(0).endpoint.type());
    }

    @Test public void vendors() throws IOException {
        String ids=new String(Files.readAllBytes(Paths.get("src/main/assets/usb_vendor_ids.txt")),StandardCharsets.US_ASCII);
        assertEquals("not in the public USB ID list",UvcDescriptors.vendor(0x5843,ids));
        assertEquals("Logitech",UvcDescriptors.vendor(0x046d,ids));
        assertEquals("in the public USB ID list, not in this app's short table",UvcDescriptors.vendor(0x0001,ids));
    }

    @Test public void fps() {
        assertEquals("30",UvcDescriptors.fps(333333));
        assertEquals("7.5",UvcDescriptors.fps(1333333));
        assertEquals("29.97",UvcDescriptors.fps(333667));
        assertEquals("?",UvcDescriptors.fps(0));
    }

    // A dump cut short (Android keeps a fixed-size copy of the descriptors): parsed as far as it goes, and flagged.
    @Test public void truncated() throws IOException {
        byte[] raw=xcTech();
        for(int n=0;n<=raw.length;n+=7) {
            UvcDescriptors u=UvcDescriptors.parse(Arrays.copyOf(raw,n));
            if(n>=18) assertEquals(0x5843,u.vendorId);
            if(n>=27 && n<raw.length) assertTrue("cut at "+n,u.truncated);
        }
        UvcDescriptors u=UvcDescriptors.parse(Arrays.copyOf(raw,600));
        assertTrue(u.truncated);
        assertEquals(11,u.streaming.get(0).formats.get(0).frames.size());
        assertEquals(2,u.streaming.get(0).formats.get(1).frames.size()); // the YUYV format and two whole frames; the third is cut
    }

    @Test public void randomBytes() {
        Random r=new Random(42);
        for(int i=0;i<5000;i++) {
            byte[] b=new byte[r.nextInt(600)]; r.nextBytes(b);
            if(b.length>1 && r.nextBoolean()) b[1]=0x01;
            UvcDescriptors u=UvcDescriptors.parse(b);
            u.speed(); for(UvcDescriptors.Unit x:u.units) { x.bits(); x.bitmap(); }
            for(UvcDescriptors.Streaming s:u.streaming) for(UvcDescriptors.Format f:s.formats) { f.name(); f.kind(); for(UvcDescriptors.Frame fr:f.frames) fr.rates(); }
            for(UvcDescriptors.Audio a:u.audio) for(UvcDescriptors.AudioSetting s:a.settings) s.describe();
        }
        assertEquals(0,UvcDescriptors.parse(null).bytes);
    }

    // A UVC 1.1 camera with H.264 as a frame-based format and an isochronous streaming endpoint (as the Logitech C930e has).
    @Test public void frameBasedIsochronous() {
        ByteArrayOutputStream o=new ByteArrayOutputStream();
        write(o,18,0x01,0x00,0x02,0xef,0x02,0x01,0x40,0x6d,0x04,0x43,0x08,0x13,0x00,0x00,0x00,0x00,0x01); // device 046d:0843, bcdUSB 2.00
        int configAt=o.size();
        write(o,9,0x02,0,0,2,1,0,0x80,250);                                // configuration, 500 mA (length patched below)
        write(o,9,0x04,0,0,0,0x0e,0x01,0x00,0);                            // VC interface 0
        write(o,13,0x24,0x01,0x10,0x01,0,0,0,0,0,0,1,1);                   // header, UVC 1.10
        write(o,18,0x24,0x02,1,0x01,0x02,0,0,0,0,0,0,0,0,3,0x22,0x02,0x02); // camera terminal: AE mode, focus abs, zoom abs, focus auto
        write(o,9,0x24,0x03,3,0x01,0x01,0,1,0);                            // output terminal 3 <- 1
        write(o,9,0x04,1,0,0,0x0e,0x02,0x00,0);                            // VS interface 1, alt 0
        write(o,14,0x24,0x01,1,0,0,0x81,0,3,2,1,1,1,0);                    // input header: 1 format, endpoint 0x81, still method 2
        write(o,28,0x24,0x10,1,1,0x48,0x32,0x36,0x34,0,0,0x10,0,0x80,0,0,0xaa,0,0x38,0x9b,0x71,16,1,0,0,0,0,1); // frame-based H264
        write(o,34,0x24,0x11,1,0,0x80,0x07,0x38,0x04,0,0,0,0,0,0,0,0,0x15,0x16,0x05,0,2,0,0,0,0,0x15,0x16,0x05,0,0x2a,0x2c,0x0a,0); // 1920x1080, 30 and 15 fps
        write(o,11,0x24,0x03,0,2,0x80,0x07,0x38,0x04,0x00,0x05);           // still image frame: 1920x1080 (then a cut-short pattern)
        write(o,9,0x04,1,1,1,0x0e,0x02,0x00,0);                            // VS interface 1, alt 1
        write(o,7,0x05,0x81,0x05,0x00,0x14,1);                             // iso endpoint 0x81, 1024 x 3
        byte[] raw=o.toByteArray();
        int total=raw.length-configAt; raw[configAt+2]=(byte)total; raw[configAt+3]=(byte)(total>>8);
        UvcDescriptors u=UvcDescriptors.parse(raw);
        assertFalse(u.truncated);
        assertEquals(0x0110,u.uvcVersion);
        assertEquals(500,u.maxPowerMa());
        assertEquals(Arrays.asList(1,5,9,17),u.cameraTerminal().bits());
        UvcDescriptors.Streaming s=u.streaming.get(0);
        assertEquals(2,s.stillMethod); assertTrue(s.stillFrameDescriptor);
        assertEquals(1,s.stillSizes.size()); assertArrayEquals(new int[]{1920,1080},s.stillSizes.get(0));
        UvcDescriptors.Format f=s.formats.get(0);
        assertEquals("H.264",f.name()); assertEquals("frame-based, H264",f.kind());
        assertEquals("1920 × 1080",f.frames.get(0).size()); assertEquals("30, 15 fps",f.frames.get(0).rates());
        UvcDescriptors.Endpoint e=u.alternates(1).get(1).endpoints.get(0);
        assertEquals(UvcDescriptors.ISOCHRONOUS,e.type()); assertEquals(3072,e.packetBytes());
        assertTrue(u.speed(),u.speed().startsWith("high-speed"));
        assertFalse(u.hasAudio());
    }
    private static void write(ByteArrayOutputStream o,int... bytes) { for(int b:bytes) o.write(b); }
}
