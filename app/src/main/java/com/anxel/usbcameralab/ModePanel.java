package com.anxel.usbcameralab;

import static com.anxel.usbcameralab.CameraSession.at;
import static com.anxel.usbcameralab.CameraSession.format;
import static com.anxel.usbcameralab.CameraSession.rate;
import static com.anxel.usbcameralab.CameraSession.same;
import static com.anxel.usbcameralab.CameraSession.shortName;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.anxel.usbcameralab.CameraSession.State;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;
import java.util.ArrayList;
import java.util.List;

// The camera mode: format, frame size and frame rate, as an exposed drop-down ("1920 × 1080 · MJPEG · 30 fps"), with a frame-
// rate drop-down when the size offers more than one, and a note under them. Locked while a video or time-lapse runs: a new
// stream size would change the aspect under a running encoder. Home has one; the mode sheet opened from the chip on the
// preview has another while it is open. Both read the session, so either can switch and both follow (update()).
@SuppressLint("SetTextI18n") // English-only UI built in code
final class ModePanel {
    final LinearLayout view;
    private final Context context;
    private final CameraSession session;
    private final TextInputLayout sizeBox, rateBox;
    private final MaterialAutoCompleteTextView sizes, rates;
    private final TextView note;
    private final List<Size> listed=new ArrayList<>(); // the frame sizes behind `sizes`' items

    ModePanel(Context context,CameraSession session) {
        this.context=context; this.session=session;
        view=M3.column(context);
        sizeBox=new TextInputLayout(context,null,R.attr.dropdownMenuStyle); sizeBox.setHint("Camera mode");
        sizes=dropdown(sizeBox);
        sizes.setOnItemClickListener((a,v,i,id) -> { Size res=listed.get(i); if(ready()) session.applyMode(at(res,rate(res,session.mode().fps)),true); update(); });
        rateBox=new TextInputLayout(context,null,R.attr.dropdownMenuStyle); rateBox.setHint("Frame rate");
        rates=dropdown(rateBox);
        rates.setOnItemClickListener((a,v,i,id) -> {
            Size now=session.mode(), res=now==null?null:session.find(now.type,now.width,now.height);
            if(ready() && res!=null) session.applyMode(at(res,res.fpsList.get(i)),true);
            update();
        });
        note=M3.small(context,"");
        view.addView(sizeBox,new LinearLayout.LayoutParams(-1,-2));
        view.addView(rateBox,M3.margins(context,-1,-2,0,8,0,0));
        view.addView(note,M3.margins(context,-1,-2,4,6,4,0));
        update();
    }
    private MaterialAutoCompleteTextView dropdown(TextInputLayout box) {
        MaterialAutoCompleteTextView t=new MaterialAutoCompleteTextView(box.getContext()); t.setInputType(InputType.TYPE_NULL); t.setKeyListener(null);
        t.setSingleLine(false); t.setMaxLines(2); // a narrow pane (landscape Home) wraps the mode rather than cutting it
        box.addView(t,new LinearLayout.LayoutParams(-1,-2));
        box.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);
        return t;
    }
    private boolean ready() { return session.state()==State.OPEN && session.mode()!=null; }

    // Fill from the session: the modes, the one streaming, its rates, and the lock while a capture is busy.
    void update() {
        Size mode=session.mode(); List<Size> modes=session.modes();
        boolean show=ready() && !modes.isEmpty();
        view.setVisibility(show?View.VISIBLE:View.GONE);
        if(!show) return;
        listed.clear(); listed.addAll(modes);
        List<String> labels=new ArrayList<>();
        for(Size m:modes) {
            int lowest=m.fpsList.get(m.fpsList.size()-1);
            labels.add(m.width+" × "+m.height+" · "+format(m)+" · "+(lowest==m.fps?"":lowest+"–")+m.fps+" fps");
        }
        sizes.setSimpleItems(labels.toArray(new String[0]));
        Size res=session.find(mode.type,mode.width,mode.height);
        boolean several=res!=null && res.fpsList.size()>1;
        sizes.setText(mode.width+" × "+mode.height+" · "+format(mode)+(several?"":" · "+mode.fps+" fps"),false);
        rateBox.setVisibility(several?View.VISIBLE:View.GONE);
        if(several) {
            List<String> r=new ArrayList<>(); for(int f:res.fpsList) r.add(f+" fps");
            rates.setSimpleItems(r.toArray(new String[0]));
            rates.setText(mode.fps+" fps",false);
        }
        CaptureController capture=session.capture;
        boolean free=!capture.isBusy();
        for(TextInputLayout box:new TextInputLayout[]{sizeBox,rateBox}) {
            box.setEnabled(free);
            box.setStartIconDrawable(free?null:M3.icon(context,R.drawable.ic_lock,M3.color(context,R.attr.colorOnSurfaceVariant)));
        }
        if(!free) note.setText(capture.timelapseRunning()?"Locked while the time-lapse runs":capture.recording()?"Locked while the video records":"Locked while the photo saves");
        else note.setText(several?res.fpsList.size()+" frame rates at this size":mode.fps+" fps only at this size");
    }

    // "1080p · 30 fps", "1440 × 1080 · 30 fps", "1080p YUYV · 5 fps": the mode on the chip over the preview.
    static String chip(Size s) {
        String size=s.width==s.height*16/9?s.height+"p":s.width+" × "+s.height;
        return size+(s.type==UVCCamera.UVC_VS_FRAME_MJPEG?"":" "+format(s))+" · "+s.fps+" fps";
    }

    // Video is 1920 × 1080 at most, and decoding a larger stream is slow (4K: about 11 fps on the A20, so 4K video is choppy).
    // A larger stream, or one under 24 fps, gets a one-tap switch to the mode the picker starts with: the largest MJPEG size
    // within 1920 × 1080 at up to 30 fps. A time-lapse keeps one frame a second at most, so it gets the largest size within
    // 1920 × 1080 at the lowest frame rate the camera offers for it: fewer frames to decode keep the phone cooler and the battery
    // going on long runs. Only a suggestion: photos may want 4K, and a smaller size is the user's choice.
    static CaptureBar.Hint hint(CameraSession session,CaptureController.Mode m) {
        Size mode=session.mode(); List<Size> modes=session.modes();
        if(session.state()!=State.OPEN || mode==null || modes.isEmpty()) return null;
        boolean big=mode.width>1920 || mode.height>1080;
        if(m==CaptureController.Mode.VIDEO) {
            Size best=null;
            for(Size o:modes) if(o.type==UVCCamera.UVC_VS_FRAME_MJPEG && o.width<=1920 && o.height<=1080) { best=at(o,rate(o,30)); break; } // largest first
            if(best==null || same(best,mode)) return null;
            Size to=best;
            if(big) return new CaptureBar.Hint("Video is 1080p at most; a larger stream makes it choppy.","Switch to "+shortName(to),() -> session.applyMode(to,true));
            if(mode.fps<24 && to.fps>mode.fps) return new CaptureBar.Hint("This camera mode runs at "+mode.fps+" fps.","Switch to "+shortName(to),() -> session.applyMode(to,true));
            return null;
        }
        if(m!=CaptureController.Mode.TIMELAPSE) return null;
        Size best=null;
        for(Size o:modes) if(o.width<=1920 && o.height<=1080) {
            int low=o.fpsList.get(o.fpsList.size()-1);
            if(best==null || o.width*o.height>best.width*best.height || (o.width*o.height==best.width*best.height && low<best.fps)) best=at(o,low);
        }
        if(best==null || same(best,mode)) return null;
        Size to=best;
        if(big) return new CaptureBar.Hint("Time-lapse is 1080p at most; a smaller, slower stream saves battery.","Switch to "+shortName(to),() -> session.applyMode(to,true));
        if(mode.width==to.width && mode.height==to.height && mode.fps>to.fps) return new CaptureBar.Hint("A slower camera stream saves battery on long time-lapses.","Switch to "+to.fps+" fps",() -> session.applyMode(to,true));
        return null;
    }
}
