package com.anxel.usbcameralab;

import static com.anxel.usbcameralab.CameraSession.cameraKey;
import static com.serenegiant.usb.UVCControl.*;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.widget.TextViewCompat;
import com.anxel.usbcameralab.CameraSession.State;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.slider.TickVisibilityMode;
import com.serenegiant.usb.UVCControl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Camera controls: every UVC control UVCAndroid can drive, shown only when the camera reports it, as Material section cards
// (Image, White balance, Exposure, Focus/zoom/position, Other) that open in place and show a summary when closed. One panel
// per screen: its view moves between the Home tab and the capture tabs' controls sheet, so both show the same widgets.
@SuppressLint("SetTextI18n") // English-only UI built in code
final class ControlsPanel {
    // What the panel needs from the screen.
    interface Host {
        void built(); // the rows are on screen (or gone): the capture bar's lock option and the restore offer follow
    }
    final LinearLayout view; // the section cards and the footnote
    private final Context context;
    private final CameraSession session;
    private final Host host;
    private final SharedPreferences prefs; // which sections are open
    private final Handler ui=new Handler(Looper.getMainLooper());
    private UVCControl control;
    private final List<Row> rows=new ArrayList<>();
    private final List<Section> sections=new ArrayList<>();
    private final Map<Row,Integer> pendingWrites=new LinkedHashMap<>(); // slider values waiting to be sent
    private final Set<Integer> noRange=new HashSet<>(); // reported controls left out because their limits gave no range
    private boolean writeQueued, updating;

    ControlsPanel(Context context,CameraSession session,Host host) {
        this.context=context; this.session=session; this.host=host;
        prefs=context.getSharedPreferences(CaptureController.PREFS,Context.MODE_PRIVATE);
        view=M3.column(context);
    }
    boolean isEmpty() { return rows.isEmpty(); }

    // ---- Every UVC control UVCAndroid can drive ----
    private static final int RANGE=0, TOGGLE=1, CHOICE=2;
    private interface Getter { int get(UVCControl c); }
    private interface Setter { void set(UVCControl c,int value); }
    private interface Limits { int[] get(UVCControl c); }
    private interface Resetter { void reset(UVCControl c); }
    private interface Units { String show(int value); }
    // TOGGLE values are 0/1 and CHOICE values index `choices`; a RANGE with autoFlag is disabled while that toggle is on.
    static final class Spec {
        final String section,label; final int flag,kind,autoFlag; final Limits limits; final Getter get; final Setter set; final Resetter reset; final Units units; final String[] choices;
        Spec(String section,String label,int flag,int kind,int autoFlag,Limits limits,Getter get,Setter set,Resetter reset,Units units,String[] choices) {
            this.section=section; this.label=label; this.flag=flag; this.kind=kind; this.autoFlag=autoFlag; this.limits=limits; this.get=get; this.set=set; this.reset=reset; this.units=units; this.choices=choices;
        }
    }
    private static Spec range(String section,String label,int flag,int autoFlag,Limits limits,Getter get,Setter set,Resetter reset,Units units) { return new Spec(section,label,flag,RANGE,autoFlag,limits,get,set,reset,units,null); }
    private static Spec toggle(String section,String label,int flag,Getter get,Setter set,Resetter reset) { return new Spec(section,label,flag,TOGGLE,0,null,get,set,reset,null,null); }
    private static Spec choice(String section,String label,int flag,Limits limits,Getter get,Setter set,Resetter reset,String... choices) { return new Spec(section,label,flag,CHOICE,0,limits,get,set,reset,null,choices); }
    private static final Units RAW=String::valueOf;
    private static final String IMAGE="Image", WB="White balance", EXPOSURE="Exposure", OPTICS="Focus, zoom and position", OTHER="Other";
    private static final Spec[] SPECS={
        range(IMAGE,"Brightness",PU_BRIGHTNESS_CONTROL,0,UVCControl::updateBrightnessLimit,UVCControl::getBrightness,UVCControl::setBrightness,UVCControl::resetBrightness,RAW),
        toggle(IMAGE,"Auto contrast",PU_CONTRAST_AUTO_CONTROL,c -> c.getContrastAuto()?1:0,(c,v) -> c.setContrastAuto(v!=0),UVCControl::resetContrastAuto),
        range(IMAGE,"Contrast",PU_CONTRAST_CONTROL,PU_CONTRAST_AUTO_CONTROL,UVCControl::updateContrastLimit,UVCControl::getContrast,UVCControl::setContrast,UVCControl::resetContrast,RAW),
        toggle(IMAGE,"Auto hue",PU_HUE_AUTO_CONTROL,c -> c.getHueAuto()?1:0,(c,v) -> c.setHueAuto(v!=0),UVCControl::resetHueAuto),
        range(IMAGE,"Hue",PU_HUE_CONTROL,PU_HUE_AUTO_CONTROL,UVCControl::updateHueLimit,UVCControl::getHue,UVCControl::setHue,UVCControl::resetHue,RAW),
        range(IMAGE,"Saturation",PU_SATURATION_CONTROL,0,UVCControl::updateSaturationLimit,UVCControl::getSaturation,UVCControl::setSaturation,UVCControl::resetSaturation,RAW),
        range(IMAGE,"Sharpness",PU_SHARPNESS_CONTROL,0,UVCControl::updateSharpnessLimit,UVCControl::getSharpness,UVCControl::setSharpness,UVCControl::resetSharpness,RAW),
        range(IMAGE,"Gamma",PU_GAMMA_CONTROL,0,UVCControl::updateGammaLimit,UVCControl::getGamma,UVCControl::setGamma,UVCControl::resetGamma,RAW),
        range(IMAGE,"Gain",PU_GAIN_CONTROL,0,UVCControl::updateGainLimit,UVCControl::getGain,UVCControl::setGain,UVCControl::resetGain,RAW),
        range(IMAGE,"Backlight compensation",PU_BACKLIGHT_COMPENSATION_CONTROL,0,UVCControl::updateBacklightCompLimit,UVCControl::getBacklightComp,UVCControl::setBacklightComp,UVCControl::resetBacklightComp,RAW),
        toggle(WB,"Auto white balance",PU_WHITE_BALANCE_TEMPERATURE_AUTO_CONTROL,c -> c.getWhiteBalanceAuto()?1:0,(c,v) -> c.setWhiteBalanceAuto(v!=0),UVCControl::resetWhiteBalanceAuto),
        range(WB,"Color temperature",PU_WHITE_BALANCE_TEMPERATURE_CONTROL,PU_WHITE_BALANCE_TEMPERATURE_AUTO_CONTROL,UVCControl::updateWhiteBalanceLimit,UVCControl::getWhiteBalance,UVCControl::setWhiteBalance,UVCControl::resetWhiteBalance,v -> v>=1000?v+" K":String.valueOf(v)), // some cameras use a small preset scale, not kelvin
        // Auto exposure on/off maps manual <-> aperture priority (and shutter priority <-> auto), as UVCControl.setExposureTimeAuto does.
        toggle(EXPOSURE,"Auto exposure",CT_AE_MODE_CONTROL,c -> c.isExposureTimeAuto()?1:0,(c,v) -> c.setExposureTimeAuto(v!=0),UVCControl::resetAutoExposureMode),
        range(EXPOSURE,"Exposure time",CT_EXPOSURE_TIME_ABSOLUTE_CONTROL,CT_AE_MODE_CONTROL,UVCControl::updateExposureTimeAbsoluteLimit,UVCControl::getExposureTimeAbsolute,UVCControl::setExposureTimeAbsolute,UVCControl::resetExposureTimeAbsolute,v -> String.format(Locale.US,"%.1f ms",v/10.0)),
        toggle(EXPOSURE,"Allow lower frame rate in low light",CT_AE_PRIORITY_CONTROL,UVCControl::getAutoExposurePriority,UVCControl::setAutoExposurePriority,UVCControl::resetAutoExposurePriority),
        range(EXPOSURE,"Iris",CT_IRIS_ABSOLUTE_CONTROL,0,UVCControl::updateIrisAbsoluteLimit,UVCControl::getIrisAbsolute,UVCControl::setIrisAbsolute,UVCControl::resetIrisAbsolute,v -> String.format(Locale.US,"f/%.1f",v/100.0)),
        choice(EXPOSURE,"Power line frequency (anti-flicker)",PU_POWER_LINE_FREQUENCY_CONTROL,UVCControl::updatePowerlineFrequencyLimit,UVCControl::getPowerlineFrequency,UVCControl::setPowerlineFrequency,UVCControl::resetPowerlineFrequency,"Off","50 Hz","60 Hz","Auto"),
        toggle(OPTICS,"Autofocus",CT_FOCUS_AUTO_CONTROL,c -> c.getFocusAuto()?1:0,(c,v) -> c.setFocusAuto(v!=0),UVCControl::resetFocusAuto),
        range(OPTICS,"Focus",CT_FOCUS_ABSOLUTE_CONTROL,CT_FOCUS_AUTO_CONTROL,UVCControl::updateFocusAbsoluteLimit,UVCControl::getFocusAbsolute,UVCControl::setFocusAbsolute,UVCControl::resetFocusAbsolute,RAW),
        range(OPTICS,"Zoom",CT_ZOOM_ABSOLUTE_CONTROL,0,UVCControl::updateZoomAbsoluteLimit,UVCControl::getZoomAbsolute,UVCControl::setZoomAbsolute,UVCControl::resetZoomAbsolute,RAW),
        range(OPTICS,"Pan",CT_PANTILT_ABSOLUTE_CONTROL,0,UVCControl::updatePanAbsoluteLimit,UVCControl::getPanAbsolute,UVCControl::setPanAbsolute,UVCControl::resetPanAbsolute,v -> String.format(Locale.US,"%.1f°",v/3600.0)),
        range(OPTICS,"Tilt",CT_PANTILT_ABSOLUTE_CONTROL,0,UVCControl::updateTiltAbsoluteLimit,UVCControl::getTiltAbsolute,UVCControl::setTiltAbsolute,UVCControl::resetTiltAbsolute,v -> String.format(Locale.US,"%.1f°",v/3600.0)),
        range(OPTICS,"Roll",CT_ROLL_ABSOLUTE_CONTROL,0,UVCControl::updateRollAbsoluteLimit,UVCControl::getRollAbsolute,UVCControl::setRollAbsolute,UVCControl::resetRollAbsolute,v -> v+"°"),
        range(OPTICS,"Digital zoom",PU_DIGITAL_MULTIPLIER_CONTROL,0,UVCControl::updateDigitalMultiplierLimit,UVCControl::getDigitalMultiplier,UVCControl::setDigitalMultiplier,UVCControl::resetDigitalMultiplier,RAW),
        range(OPTICS,"Digital zoom limit",PU_DIGITAL_MULTIPLIER_LIMIT_CONTROL,0,UVCControl::updateDigitalMultiplierLimitLimit,UVCControl::getDigitalMultiplierLimit,UVCControl::setDigitalMultiplierLimit,UVCControl::resetDigitalMultiplierLimit,RAW),
        toggle(OTHER,"Privacy shutter",CT_PRIVACY_CONTROL,c -> c.getPrivacy()?1:0,(c,v) -> c.setPrivacy(v!=0),UVCControl::resetPrivacy),
        choice(OTHER,"Scanning mode",CT_SCANNING_MODE_CONTROL,UVCControl::updateScanningModeLimit,UVCControl::getScanningMode,UVCControl::setScanningMode,UVCControl::resetScanningMode,"Interlaced","Progressive"),
    };
    // Reported controls with no settings UI here: relative ones are "move at this speed" commands, the rest are packed or analog-only.
    private static final int[] UNLISTED_FLAGS={CT_EXPOSURE_TIME_RELATIVE_CONTROL,CT_FOCUS_RELATIVE_CONTROL,CT_IRIS_RELATIVE_CONTROL,CT_ZOOM_RELATIVE_CONTROL,CT_PANTILT_RELATIVE_CONTROL,CT_ROLL_RELATIVE_CONTROL,CT_FOCUS_SIMPLE_CONTROL,CT_WINDOW_CONTROL,PU_WHITE_BALANCE_COMPONENT_CONTROL,PU_WHITE_BALANCE_COMPONENT_AUTO_CONTROL,PU_ANALOG_VIDEO_STANDARD_CONTROL,PU_ANALOG_LOCK_STATUS_CONTROL};
    private static final String[] UNLISTED_NAMES={"exposure (relative)","focus (relative)","iris (relative)","zoom (relative)","pan/tilt (relative)","roll (relative)","focus (simple)","window","white balance components","auto white balance components","analog video standard","analog lock status"};
    private static int sectionIcon(String section) {
        switch(section) {
            case IMAGE: return R.drawable.ic_image;
            case WB: return R.drawable.ic_wb_sunny;
            case EXPOSURE: return R.drawable.ic_exposure;
            case OPTICS: return R.drawable.ic_focus;
            default: return R.drawable.ic_more_horiz;
        }
    }

    // One control on screen. show() reads the camera and updates the widgets without writing back.
    private final class Row {
        final Spec spec; int min,max; View view; TextView title,value; Slider slider; MaterialSwitch toggle; MaterialButtonToggleGroup group; boolean dragging;
        Row(Spec spec) { this.spec=spec; }
        boolean build() {
            if(spec.kind==RANGE) {
                int[] l=spec.limits.get(control); if(l==null || l.length<2 || l[1]<=l[0]) return false;
                min=l[0]; max=l[1];
                LinearLayout box=M3.column(context); box.setPadding(0,M3.dp(context,4),0,0);
                LinearLayout line=M3.row(context); box.addView(line);
                title=M3.body(context,spec.label); line.addView(title,M3.grow());
                value=M3.text(context,"",R.attr.textAppearanceBodyMedium,R.attr.colorOnSurfaceVariant); value.setFontFeatureSettings("tnum"); line.addView(value);
                slider=new Slider(context); slider.setValueFrom(min); slider.setValueTo(max); slider.setStepSize(1);
                slider.setTickVisibilityMode(TickVisibilityMode.TICK_VISIBILITY_HIDDEN);
                slider.setTrackHeight(M3.dp(context,10)); slider.setThumbHeight(M3.dp(context,30)); // the mockups' slimmer slider
                slider.setLabelFormatter(v -> spec.units.show(Math.round(v))); // also what TalkBack reads: "Exposure time, 7.8 ms"
                slider.setContentDescription(spec.label);
                box.addView(slider,new LinearLayout.LayoutParams(-1,-2));
                slider.addOnChangeListener((s,v,user) -> {
                    value.setText(spec.units.show(Math.round(v)));
                    if(!user) return;
                    queueWrite(this,Math.round(v));
                    if(!dragging) scheduleRefresh(); // a key or accessibility step: show what the camera applied
                });
                slider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
                    @Override public void onStartTrackingTouch(Slider s) { dragging=true; }
                    @Override public void onStopTrackingTouch(Slider s) { dragging=false; queueWrite(Row.this,Math.round(s.getValue())); scheduleRefresh(); } // show what the camera applied
                });
                view=box;
            } else if(spec.kind==TOGGLE) {
                toggle=new MaterialSwitch(context); toggle.setText(spec.label); toggle.setMinHeight(M3.dp(context,48));
                bodyText(toggle);
                toggle.setOnCheckedChangeListener((b,on) -> { if(!updating) userChanged(this,on?1:0); });
                view=toggle;
            } else {
                int lo=0, hi=spec.choices.length-1;
                int[] l=spec.limits.get(control); if(l!=null && l.length>=2 && l[1]>l[0]) { lo=Math.max(lo,l[0]); hi=Math.min(hi,l[1]); }
                LinearLayout box=M3.column(context); box.setPadding(0,M3.dp(context,6),0,M3.dp(context,4));
                title=M3.body(context,spec.label); box.addView(title);
                group=new MaterialButtonToggleGroup(context); group.setSingleSelection(true); group.setSelectionRequired(true);
                for(int v=lo;v<=hi;v++) {
                    MaterialButton b=new MaterialButton(context,null,R.attr.materialButtonOutlinedStyle); b.setId(View.generateViewId()); b.setTag(v); b.setText(spec.choices[v]);
                    b.setPadding(M3.dp(context,4),0,M3.dp(context,4),0); b.setMinWidth(0); b.setMinimumWidth(0); b.setSingleLine(true);
                    group.addView(b,new LinearLayout.LayoutParams(0,-2,1));
                }
                group.addOnButtonCheckedListener((g,id,checked) -> { View b=g.findViewById(id); if(checked && !updating && b!=null) userChanged(this,(Integer)b.getTag()); });
                box.addView(group,new LinearLayout.LayoutParams(-1,-2));
                view=box;
            }
            show(); return true;
        }
        void show() {
            int v=spec.get.get(control);
            updating=true;
            if(slider!=null) { int c=Math.max(min,Math.min(max,v)); if(!dragging) slider.setValue(c); value.setText(spec.units.show(c)); }
            else if(toggle!=null) { toggle.setChecked(v!=0); toggle.setText(toggleText(v!=0)); }
            else for(int i=0;i<group.getChildCount();i++) if((Integer)group.getChildAt(i).getTag()==v) group.check(group.getChildAt(i).getId());
            updating=false;
        }
        // An auto mode the time-lapse turned off says so under its name.
        CharSequence toggleText(boolean on) {
            if(on || !session.locked.contains(spec.flag)) return spec.label;
            SpannableStringBuilder s=new SpannableStringBuilder(spec.label).append('\n');
            int start=s.length(); s.append("Off: held by the time-lapse lock");
            s.setSpan(new RelativeSizeSpan(0.86f),start,s.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(M3.color(context,R.attr.colorOnSurfaceVariant)),start,s.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return s;
        }
        int current() {
            if(slider!=null) return Math.round(slider.getValue());
            if(toggle!=null) return toggle.isChecked()?1:0;
            View b=group.findViewById(group.getCheckedButtonId()); return b==null?-1:(Integer)b.getTag();
        }
        String describe() {
            if(slider!=null) return spec.label+" ["+min+".."+max+"]="+current();
            if(toggle!=null) return spec.label+"="+toggle.isChecked();
            int c=current(); return spec.label+"="+(c<0?"?":c);
        }
    }
    // Material's body text for a switch's label: a MaterialSwitch made in code keeps the button text style.
    private static void bodyText(TextView t) { TextViewCompat.setTextAppearance(t,M3.style(t.getContext(),R.attr.textAppearanceBodyMedium)); t.setTextColor(M3.color(t.getContext(),R.attr.colorOnSurface)); }

    // A card per section: a header (icon, name, summary when closed, chevron) that opens or closes the rows under it.
    private final class Section {
        final String name; final MaterialCardView card; final LinearLayout body; final TextView summary; final ImageView chevron; final View header;
        final List<Row> rows=new ArrayList<>(); boolean open;
        Section(String name) {
            this.name=name;
            LinearLayout content=M3.column(context); content.setPadding(M3.dp(context,16),M3.dp(context,4),M3.dp(context,16),M3.dp(context,4));
            card=M3.card(context,content);
            LinearLayout head=M3.row(context); head.setMinimumHeight(M3.dp(context,48)); header=head;
            head.addView(M3.image(context,sectionIcon(name),M3.color(context,R.attr.colorOnSurfaceVariant),20));
            TextView title=M3.text(context,name,R.attr.textAppearanceTitleSmall,R.attr.colorOnSurface); title.setTextSize(TypedValue.COMPLEX_UNIT_SP,15);
            head.addView(title,M3.margins(context,0,-2,12,0,8,0)); ((LinearLayout.LayoutParams)title.getLayoutParams()).weight=1;
            summary=M3.small(context,""); summary.setGravity(Gravity.END); head.addView(summary,M3.margins(context,-2,-2,0,0,8,0));
            chevron=M3.image(context,R.drawable.ic_expand_more,M3.color(context,R.attr.colorOnSurfaceVariant),24); head.addView(chevron);
            TypedValue ripple=new TypedValue(); context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,ripple,true);
            head.setBackgroundResource(ripple.resourceId); head.setClickable(true); head.setFocusable(true);
            head.setOnClickListener(v -> setOpen(!open,true));
            content.addView(head,new LinearLayout.LayoutParams(-1,-2));
            body=M3.column(context); body.setPadding(0,0,0,M3.dp(context,8)); content.addView(body,new LinearLayout.LayoutParams(-1,-2));
            open=prefs.getBoolean("open "+name,IMAGE.equals(name));
        }
        void setOpen(boolean on,boolean save) {
            open=on; body.setVisibility(on?View.VISIBLE:View.GONE);
            chevron.setImageDrawable(M3.icon(context,on?R.drawable.ic_expand_less:R.drawable.ic_expand_more,M3.color(context,R.attr.colorOnSurfaceVariant)));
            if(save) prefs.edit().putBoolean("open "+name,on).apply();
            summarize();
        }
        // Closed: "7 controls", "Auto", "Manual · 50 Hz"; open: nothing, the rows say it.
        void summarize() {
            String s;
            Row auto=null, choice=null;
            for(Row r:rows) { if(r.toggle!=null && auto==null) for(Row o:rows) if(o.spec.autoFlag==r.spec.flag) { auto=r; break; } if(r.group!=null && choice==null) choice=r; }
            if(auto!=null) s=auto.toggle.isChecked()?"Auto":"Manual";
            else s=rows.size()==1?"1 control":rows.size()+" controls";
            if(choice!=null && choice.current()>=0) s+=" · "+choice.spec.choices[choice.current()];
            summary.setText(open?"":s); summary.setVisibility(open?View.GONE:View.VISIBLE);
            header.setContentDescription(name+(open?"":", "+s)+". "+(open?"Tap to close.":"Tap to open."));
        }
    }

    // "Lock exposure / WB / focus": the auto modes a time-lapse turns off, so frames don't drift or flicker.
    private static boolean lockable(Spec s) { return s.flag==CT_AE_MODE_CONTROL || s.flag==PU_WHITE_BALANCE_TEMPERATURE_AUTO_CONTROL || s.flag==CT_FOCUS_AUTO_CONTROL; }
    boolean canLock() { for(Row r:rows) if(r.toggle!=null && lockable(r.spec)) return true; return false; }
    // Before a time-lapse: each lockable auto mode that is on goes off, and the camera holds what auto had chosen. Nothing is
    // written back: the XC-TECH camera reports color temperature 4 (of its 1..5 presets) while auto white balance runs, and
    // writing that 4 turned the picture orange, while only switching auto off kept it. The camera remembers manual mode, also
    // across reconnects, hence the offer to turn auto back on.
    void lockAuto() {
        if(session.state()!=State.OPEN || control==null) return;
        StringBuilder log=new StringBuilder();
        for(Row t:rows) if(t.toggle!=null && lockable(t.spec) && t.toggle.isChecked()) {
            Row manual=null; for(Row r:rows) if(r.spec.autoFlag==t.spec.flag) manual=r;
            int value=manual==null?0:manual.spec.get.get(control);
            t.spec.set.set(control,0);
            if(!session.locked.contains(t.spec.flag)) session.locked.add(t.spec.flag);
            log.append(log.length()==0?"":", ").append(t.spec.label).append(" off").append(manual==null?"":" ("+manual.spec.label+" read "+value+")");
        }
        if(!session.locked.isEmpty()) session.lockedCamera=cameraKey(session.selected());
        android.util.Log.i("UsbCameraLab","lock: "+(log.length()==0?"nothing on to lock":log));
        ui.removeCallbacks(refresh); refresh();
    }
    // After a time-lapse, or at the next open of that camera when the run ended with it: the auto modes to offer back, as
    // "auto white balance and auto exposure", or null when there is nothing to offer now.
    String lockedNames() {
        List<Spec> locked=locked();
        if(locked.isEmpty() || session.state()!=State.OPEN || control==null || session.capture.isBusy()) return null;
        if(!cameraKey(session.selected()).equals(session.lockedCamera)) return null;
        StringBuilder names=new StringBuilder();
        for(int i=0;i<locked.size();i++) names.append(i==0?"":i==locked.size()-1?" and ":", ").append(locked.get(i).label.toLowerCase(Locale.US));
        return names.toString();
    }
    void restoreAuto() {
        if(session.state()!=State.OPEN || control==null) return; // the camera went away meanwhile: asked again at its next open
        List<Spec> locked=locked();
        for(Spec s:locked) s.set.set(control,1);
        android.util.Log.i("UsbCameraLab","lock: restored "+locked.size()+" auto mode(s)");
        session.locked.clear(); scheduleRefresh();
    }
    void keepManual() { session.locked.clear(); scheduleRefresh(); }
    // The session's locked auto modes, in the order they were turned off.
    private List<Spec> locked() {
        List<Spec> specs=new ArrayList<>();
        for(int flag:session.locked) for(Spec s:SPECS) if(s.kind==TOGGLE && s.flag==flag) specs.add(s);
        return specs;
    }

    // UVCControl: support flags can be wrong right after open, so the rows come a moment later.
    void showSoon() { ui.removeCallbacks(show); ui.postDelayed(show,700); }
    private final Runnable show=this::show;
    private void show() {
        clear(); if(session.state()!=State.OPEN) return;
        control=session.helper().getUVCControl(); if(control==null) { host.built(); return; }
        long started=SystemClock.uptimeMillis();
        Section section=null;
        for(Spec s:SPECS) {
            if(!control.checkSupportFlag(s.flag)) continue;
            Row r=new Row(s);
            if(!r.build()) { noRange.add(s.flag); android.util.Log.w("UsbCameraLab","control "+s.label+" reported but has no usable range"); continue; }
            if(section==null || !s.section.equals(section.name)) { section=new Section(s.section); sections.add(section); }
            section.body.addView(r.view,new LinearLayout.LayoutParams(-1,-2)); section.rows.add(r); rows.add(r);
        }
        StringBuilder unlisted=new StringBuilder();
        for(int i=0;i<UNLISTED_FLAGS.length;i++) if(control.checkSupportFlag(UNLISTED_FLAGS[i])) unlisted.append(unlisted.length()==0?"":", ").append(UNLISTED_NAMES[i]);
        if(rows.isEmpty()) view.addView(M3.body(context,"The camera reports no adjustable controls"),M3.margins(context,-1,-2,16,8,16,8));
        for(Section s:sections) { view.addView(s.card,M3.margins(context,-1,-2,12,0,12,8)); s.setOpen(s.open,false); }
        if(unlisted.length()>0) view.addView(M3.small(context,"Also reported, not adjustable here: "+unlisted),M3.margins(context,-1,-2,24,4,24,12));
        updateEnabled();
        StringBuilder log=new StringBuilder();
        for(Row r:rows) log.append(log.length()==0?"":"; ").append(r.describe());
        android.util.Log.i("UsbCameraLab","controls ("+(SystemClock.uptimeMillis()-started)+" ms): "+log+(unlisted.length()>0?" | unlisted: "+unlisted:""));
        host.built();
    }
    // The camera left OPEN: no rows, nothing pending.
    void clear() {
        ui.removeCallbacksAndMessages(null); writeQueued=false; pendingWrites.clear();
        control=null; rows.clear(); sections.clear(); noRange.clear(); view.removeAllViews();
    }

    // ---- For the Camera info page ----
    // The name this panel gives a UVC control flag ("Auto exposure", "Pan / Tilt", "Focus (relative)"), or null when it has none.
    static String label(int flag) {
        StringBuilder s=new StringBuilder();
        for(Spec spec:SPECS) if(spec.flag==flag) s.append(s.length()==0?"":" / ").append(spec.label);
        if(s.length()>0) return s.toString();
        for(int i=0;i<UNLISTED_FLAGS.length;i++) if(UNLISTED_FLAGS[i]==flag) return Character.toUpperCase(UNLISTED_NAMES[i].charAt(0))+UNLISTED_NAMES[i].substring(1);
        return null;
    }
    // What the panel does with a control the camera declares: a row (SHOWN); reported, but its limits gave no range, so no row
    // (NO_RANGE); relative, packed or analog, named in the footnote (NOT_ADJUSTABLE); not reported by the library's support
    // flags (NOT_REPORTED); no settings UI in this app (NOT_IN_APP); or a row once the camera is open and read (WHEN_OPEN).
    static final int SHOWN=0, NO_RANGE=1, NOT_ADJUSTABLE=2, NOT_REPORTED=3, NOT_IN_APP=4, WHEN_OPEN=5;
    boolean ready() { return control!=null; } // the rows were built for the open camera
    int status(int flag) {
        boolean adjustable=false;
        for(Spec s:SPECS) if(s.flag==flag) adjustable=true;
        if(!adjustable) { for(int f:UNLISTED_FLAGS) if(f==flag) return NOT_ADJUSTABLE; return NOT_IN_APP; }
        if(control==null) return WHEN_OPEN;
        for(Row r:rows) if(r.spec.flag==flag) return SHOWN;
        return noRange.contains(flag)?NO_RANGE:NOT_REPORTED;
    }
    // A slider disabled while its auto mode is on: the camera picks that value itself.
    private void updateEnabled() {
        for(Row r:rows) if(r.slider!=null && r.spec.autoFlag!=0) {
            boolean auto=false;
            for(Row t:rows) if(t.toggle!=null && t.spec.flag==r.spec.autoFlag) auto=t.toggle.isChecked();
            r.slider.setEnabled(!auto); r.title.setAlpha(auto?0.45f:1f); r.value.setAlpha(auto?0.45f:1f);
        }
        for(Section s:sections) s.summarize();
    }
    // Toggles and choices are written at once, then everything is re-read: the camera may refuse a value or move others.
    private void userChanged(Row row,int value) {
        if(session.state()!=State.OPEN || control==null) return;
        row.spec.set.set(control,value);
        updateEnabled(); scheduleRefresh();
    }
    void resetAll() {
        if(session.state()!=State.OPEN || control==null) return;
        pendingWrites.clear();
        for(Row r:rows) if(r.spec.kind==RANGE) r.spec.reset.reset(control);
        for(Row r:rows) if(r.spec.kind!=RANGE) r.spec.reset.reset(control); // auto modes last, so they win over manual values
        scheduleRefresh();
    }
    private final Runnable refresh=this::refresh;
    private void refresh() {
        if(session.state()!=State.OPEN || control==null) return;
        long started=SystemClock.uptimeMillis(); StringBuilder log=new StringBuilder();
        for(Row r:rows) { r.show(); log.append(log.length()==0?"":"; ").append(r.describe()); }
        updateEnabled();
        android.util.Log.i("UsbCameraLab","refresh ("+(SystemClock.uptimeMillis()-started)+" ms): "+log);
    }
    void scheduleRefresh() { ui.removeCallbacks(refresh); ui.postDelayed(refresh,400); }
    // Live sliders: coalesce drag updates and send at most one UVC control write per slider every 50 ms.
    private void queueWrite(Row row,int value) {
        pendingWrites.put(row,value);
        if(!writeQueued) { writeQueued=true; ui.postDelayed(applyWrites,50); }
    }
    private final Runnable applyWrites=this::applyWrites;
    private void applyWrites() {
        writeQueued=false;
        if(session.state()==State.OPEN && control!=null) for(Map.Entry<Row,Integer> e:pendingWrites.entrySet()) e.getKey().spec.set.set(control,e.getValue());
        pendingWrites.clear();
    }
}
