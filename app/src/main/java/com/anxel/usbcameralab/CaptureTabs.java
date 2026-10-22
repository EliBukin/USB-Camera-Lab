package com.anxel.usbcameralab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

// The panels of the Photo, Video and Time-lapse tabs, under the picture in portrait (landscape and full screen have none: the
// picture and its pills say it there). CaptureBar fills them.
//  Photo: the photo size and where photos go.
//  Video: the camera-mode hint, the sound source, the output and folder.
//  Time-lapse: idle, the hint, the interval presets with what an hour becomes, and the lock; running, a warning, the four
//   read-outs (frames, video so far, elapsed, part) and the hour's bar.
// Each also shows "Go to …" while another tab's video or time-lapse runs.
@SuppressLint("SetTextI18n") // English-only UI built in code
final class CaptureTabs {
    interface Host {
        void intervalPicked(int seconds);
        void lockChanged(boolean on);
    }
    final ScrollView photo, video, timelapse;
    final M3.Banner photoGoTo, videoGoTo, videoHint, lapseGoTo, lapseHint, lapseWarning;
    private final Context context;
    private final TextView photoSize, soundTitle, soundNote, output, outputNote, perHour;
    private final ChipGroup intervals;
    private final LinearLayout lapseIdle, lapseRunning, lockRow;
    private final MaterialSwitch lockSwitch;
    private final TextView frames, videoSoFar, elapsed, partLabel, partNext;
    private final LinearProgressIndicator hour;
    private boolean updating;

    CaptureTabs(Context c,Host host) {
        context=c;
        // Photo
        LinearLayout p=M3.column(c); p.setPadding(0,M3.dp(c,12),0,M3.dp(c,12));
        photoGoTo=new M3.Banner(c); p.addView(photoGoTo.view,M3.margins(c,-1,-2,12,0,12,8));
        LinearLayout size=M3.column(c); size.setGravity(Gravity.CENTER_HORIZONTAL);
        size.addView(M3.small(c,"Photo size"),new LinearLayout.LayoutParams(-2,-2));
        photoSize=M3.text(c,"",R.attr.textAppearanceHeadlineMedium,R.attr.colorOnSurface); photoSize.setFontFeatureSettings("tnum"); size.addView(photoSize,new LinearLayout.LayoutParams(-2,-2));
        size.addView(M3.small(c,"JPEG · saved to "+MediaFiles.PHOTO_DIR),new LinearLayout.LayoutParams(-2,-2));
        p.addView(size,M3.margins(c,-1,-2,16,40,16,0));
        photo=scroll(p);
        // Video
        LinearLayout v=M3.column(c); v.setPadding(0,M3.dp(c,12),0,M3.dp(c,12));
        videoGoTo=new M3.Banner(c); v.addView(videoGoTo.view,M3.margins(c,-1,-2,12,0,12,8));
        videoHint=new M3.Banner(c); v.addView(videoHint.view,M3.margins(c,-1,-2,12,0,12,8));
        TextView[] sound=infoCard(v,R.drawable.ic_mic); soundTitle=sound[0]; soundNote=sound[1];
        TextView[] out=infoCard(v,R.drawable.ic_capture); output=out[0]; outputNote=out[1];
        v.addView(M3.small(c,"Leave the app and recording goes on; Stop is in the notification."),M3.margins(c,-1,-2,24,4,24,0));
        video=scroll(v);
        // Time-lapse
        LinearLayout t=M3.column(c); t.setPadding(0,M3.dp(c,12),0,M3.dp(c,12));
        lapseGoTo=new M3.Banner(c); t.addView(lapseGoTo.view,M3.margins(c,-1,-2,12,0,12,8));
        lapseHint=new M3.Banner(c); t.addView(lapseHint.view,M3.margins(c,-1,-2,12,0,12,8));
        lapseWarning=new M3.Banner(c); t.addView(lapseWarning.view,M3.margins(c,-1,-2,12,0,12,8));
        lapseIdle=M3.column(c); t.addView(lapseIdle);
        lapseIdle.addView(M3.label(c,"Interval"),M3.margins(c,-1,-2,16,4,16,4));
        intervals=new ChipGroup(c); intervals.setSingleLine(true); intervals.setSingleSelection(true); intervals.setSelectionRequired(true);
        intervals.setChipSpacingHorizontal(M3.dp(c,8)); intervals.setPadding(M3.dp(c,16),0,M3.dp(c,16),0); intervals.setClipToPadding(false);
        for(int s:TimelapseRecorder.INTERVALS) {
            Chip chip=new Chip(c,null,R.attr.filterChipStyle); chip.setText(TimelapseRecorder.every(s)); chip.setTag(s); chip.setId(View.generateViewId()); chip.setCheckable(true);
            chip.setContentDescription("Every "+TimelapseRecorder.every(s)+": an hour makes "+CaptureBar.perHour(s)+" of video");
            intervals.addView(chip);
        }
        intervals.setOnCheckedStateChangeListener((g,ids) -> {
            if(updating || ids.isEmpty()) return;
            View chosen=g.findViewById(ids.get(0)); if(chosen==null) return;
            chosen.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            host.intervalPicked((Integer)chosen.getTag());
        });
        HorizontalScrollView strip=new HorizontalScrollView(c); strip.setHorizontalScrollBarEnabled(false); strip.addView(intervals);
        lapseIdle.addView(strip,new LinearLayout.LayoutParams(-1,-2));
        perHour=M3.small(c,""); lapseIdle.addView(perHour,M3.margins(c,-1,-2,16,4,16,4));
        lockRow=M3.row(c); lockRow.setMinimumHeight(M3.dp(c,56));
        LinearLayout words=M3.column(c);
        words.addView(M3.body(c,"Lock exposure, white balance and focus"));
        words.addView(M3.small(c,"at the start; offers auto back afterwards"));
        lockRow.addView(words,M3.grow());
        lockSwitch=new MaterialSwitch(c); lockSwitch.setContentDescription("Lock exposure, white balance and focus at the start of the time-lapse");
        lockSwitch.setOnCheckedChangeListener((b,on) -> { if(!updating) host.lockChanged(on); });
        lockRow.addView(lockSwitch);
        lockRow.setOnClickListener(x -> lockSwitch.toggle());
        lapseIdle.addView(lockRow,M3.margins(c,-1,-2,16,4,16,0));
        lapseRunning=M3.column(c); t.addView(lapseRunning);
        LinearLayout top=M3.row(c), bottom=M3.row(c);
        frames=stat(top,"Frames",true); videoSoFar=stat(top,"Video so far",false);
        elapsed=stat(bottom,"Elapsed",true); partNext=stat(bottom,"Part 1 · next file at",false);
        partLabel=(TextView)((LinearLayout)partNext.getParent()).getChildAt(0);
        lapseRunning.addView(top,M3.margins(c,-1,-2,12,0,12,8)); lapseRunning.addView(bottom,M3.margins(c,-1,-2,12,0,12,0));
        hour=new LinearProgressIndicator(c); hour.setMax(1000); hour.setTrackCornerRadius(M3.dp(c,3)); hour.setTrackThickness(M3.dp(c,6));
        lapseRunning.addView(hour,M3.margins(c,-1,-2,16,14,16,0));
        lapseRunning.addView(M3.small(c,"Hourly parts are saved one by one"),M3.margins(c,-1,-2,16,4,16,0));
        timelapse=scroll(t);
    }
    private ScrollView scroll(LinearLayout content) {
        ScrollView s=new ScrollView(context); s.setFillViewport(true); s.addView(content,new ScrollView.LayoutParams(-1,-2));
        s.setVisibility(View.GONE); return s;
    }
    // A card with a round icon, a line and a note under it: the sound source, the output.
    private TextView[] infoCard(LinearLayout parent,int icon) {
        LinearLayout content=M3.row(context); content.setPadding(M3.dp(context,14),M3.dp(context,10),M3.dp(context,14),M3.dp(context,10));
        content.addView(M3.avatar(context,icon,M3.color(context,R.attr.colorSecondaryContainer),M3.color(context,R.attr.colorOnSecondaryContainer)));
        LinearLayout words=M3.column(context);
        TextView title=M3.body(context,""), note=M3.small(context,"");
        words.addView(title); words.addView(note);
        content.addView(words,M3.margins(context,0,-2,12,0,0,0)); ((LinearLayout.LayoutParams)words.getLayoutParams()).weight=1;
        parent.addView(M3.card(context,content),M3.margins(context,-1,-2,12,0,12,8));
        return new TextView[]{title,note};
    }
    // A read-out tile: a small label over a large number.
    private TextView stat(LinearLayout row,String label,boolean first) {
        LinearLayout tile=M3.column(context); tile.setPadding(M3.dp(context,12),M3.dp(context,8),M3.dp(context,12),M3.dp(context,8));
        android.graphics.drawable.GradientDrawable back=new android.graphics.drawable.GradientDrawable(); back.setCornerRadius(M3.dp(context,12)); back.setColor(M3.color(context,R.attr.colorSurfaceContainerLow));
        tile.setBackground(back);
        tile.addView(M3.small(context,label));
        TextView value=M3.text(context,"",R.attr.textAppearanceTitleLarge,R.attr.colorOnSurface); value.setFontFeatureSettings("tnum"); value.setTextSize(TypedValue.COMPLEX_UNIT_SP,20);
        tile.addView(value);
        row.addView(tile,M3.margins(context,0,-2,first?0:8,0,0,0)); ((LinearLayout.LayoutParams)tile.getLayoutParams()).weight=1;
        return value;
    }

    void photo(String size) { photoSize.setText(size==null?"—":size); }
    // source: the recorder's ("phone mic (camera mic is silent)"), or null before a recording, with what will happen.
    void sound(String title,String note) { soundTitle.setText(title); soundNote.setText(note); soundNote.setVisibility(note==null?View.GONE:View.VISIBLE); }
    // sizeLabel: the controller's ("1920 × 1080", or "1920 × 1080\nfrom 2160p").
    void output(String sizeLabel) {
        String[] lines=sizeLabel==null?new String[]{"—"}:sizeLabel.split("\n");
        output.setText(lines[0]+" · H.264 MP4");
        outputNote.setText((lines.length>1?lines[1]+" · ":"")+MediaFiles.VIDEO_DIR);
    }
    void timelapse(boolean running,int interval,String sizeLabel,boolean canLock,boolean lock,boolean busy) {
        updating=true;
        lapseIdle.setVisibility(running?View.GONE:View.VISIBLE); lapseRunning.setVisibility(running?View.VISIBLE:View.GONE);
        for(int i=0;i<intervals.getChildCount();i++) { Chip chip=(Chip)intervals.getChildAt(i); chip.setChecked((Integer)chip.getTag()==interval); chip.setEnabled(!busy); }
        String size=sizeLabel==null?"":" · "+sizeLabel.split("\n")[0];
        perHour.setText("1 h → "+CaptureBar.perHour(interval)+" of video · "+TimelapseRecorder.FPS+" fps"+size);
        lockRow.setVisibility(canLock?View.VISIBLE:View.GONE); lockSwitch.setChecked(lock); lockSwitch.setEnabled(!busy); lockRow.setEnabled(!busy);
        updating=false;
    }
    void timelapseProgress(int n,long ms) {
        frames.setText(String.valueOf(n)); videoSoFar.setText(TimelapseRecorder.length(n)); elapsed.setText(CaptureBar.clock(ms));
        long part=ms/TimelapseRecorder.PART_MS+1;
        partLabel.setText("Part "+part+" · next file at"); partNext.setText(CaptureBar.clock(part*TimelapseRecorder.PART_MS));
        hour.setProgress((int)(ms%TimelapseRecorder.PART_MS*1000/TimelapseRecorder.PART_MS));
    }
}
