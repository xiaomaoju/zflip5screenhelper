package io.github.flipcover.controls;

import android.content.res.ColorStateList;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

/** Artwork-led media detail with compact metadata and an optional player list. */
final class MediaDetailView extends LinearLayout {
    private final CoverService owner;
    private final MediaSessions sessions;
    private final MediaCardView card;
    private final SeekBar progress;
    private final TextView time, duration;
    private final LinearLayout players;
    private final Button permission;
    private final ImageButton output, playerPicker, openPlayer;
    private boolean attached, dragging, playersExpanded;
    private int availableHeight = Integer.MAX_VALUE;
    private android.media.session.MediaSession.Token dragPlayer;
    private long dragDuration;
    private final Runnable update = this::render;
    private final Runnable tick = new Runnable() { @Override public void run() { if (!attached) return; updateTime(); if (sessions.info().playing()) postDelayed(this, 1000); } };
    MediaDetailView(CoverService owner) {
        super(owner.screenContext); this.owner = owner; sessions = owner.mediaSessions(); setOrientation(VERTICAL); setTag("media-detail");
        output = tool(R.drawable.ic_ms_volume_up, "选择媒体输出", () -> owner.showDetails("volume", this)); output.setTag("media-output");
        playerPicker = tool(R.drawable.ic_ms_apps, "切换播放器", () -> { playersExpanded = !playersExpanded; render(); }); playerPicker.setTag("media-player-picker");
        openPlayer = tool(R.drawable.ic_ms_music_note, "打开播放器", owner::openMediaPlayer); openPlayer.setTag("media-open-player");
        card = new MediaCardView(owner, false); addView(card, new LayoutParams(-1, -2));
        LinearLayout timeline = Ui.column(getContext()); timeline.setTag("media-timeline");
        progress = new SeekBar(getContext()); progress.setTag("media-progress"); progress.setMax(1000); progress.setContentDescription("播放进度"); Ui.styleSlider(progress); progress.setMinimumHeight(0); progress.setPadding(Ui.dp(getContext(), 3), 0, Ui.dp(getContext(), 3), 0); progress.setProgressTintList(ColorStateList.valueOf(Ui.TEXT)); progress.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        timeline.addView(progress, new LayoutParams(-1, Ui.dp(getContext(), 24)));
        LinearLayout times = Ui.row(getContext()); time = Ui.text(getContext(), "", 8, Ui.MUTED); time.setTag("media-time"); time.setSingleLine(); duration = Ui.text(getContext(), "", 8, Ui.MUTED); duration.setTag("media-duration"); duration.setGravity(Gravity.RIGHT); duration.setSingleLine();
        times.addView(time, new LayoutParams(0, -2, 1)); times.addView(duration, new LayoutParams(0, -2, 1)); timeline.addView(times, new LayoutParams(-1, -2)); card.beforeTransport(timeline);
        permission = Ui.button(getContext(), "允许通知使用权", () -> owner.openSettings("permissions")); permission.setTextSize(10); permission.setMinHeight(Ui.dp(getContext(), 30)); permission.setMinimumHeight(Ui.dp(getContext(), 30)); addView(permission, new LayoutParams(-1, -2));
        players = Ui.column(getContext()); players.setTag("media-players"); addView(players, new LayoutParams(-1, -2));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar slider) { dragging = true; dragPlayer = sessions.controller() == null ? null : sessions.controller().getSessionToken(); dragDuration = sessions.info().duration(); }
            @Override public void onProgressChanged(SeekBar slider, int value, boolean user) { if (user) { long position = Math.round((dragging ? dragDuration : sessions.info().duration()) * (value / 1000d)); time.setText(format(position)); if (!dragging) sessions.seek(position); } }
            @Override public void onStopTrackingTouch(SeekBar slider) { dragging = false; if (dragPlayer == null || sessions.controller() == null || !dragPlayer.equals(sessions.controller().getSessionToken()) || !sessions.seek(Math.round(dragDuration * (slider.getProgress() / 1000d)))) owner.message("播放器不支持调整进度"); updateTime(); }
        });
        render();
    }
    private ImageButton tool(int icon, String label, Runnable action) { ImageButton button = RuntimeVisuals.button(getContext(), icon, label, action); int inset = Ui.dp(getContext(), 6); button.setPadding(inset, inset, inset, inset); return button; }
    View[] toolbar() { return new View[]{output, playerPicker, openPlayer}; }
    void availableHeight(int height) { availableHeight = height; }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.AT_MOST) widthSpec = MeasureSpec.makeMeasureSpec(Math.min(MeasureSpec.getSize(widthSpec), Ui.dp(getContext(), 212)), MeasureSpec.EXACTLY);
        int extras = 0, free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (View view : new View[]{permission, players}) if (view.getVisibility() != GONE) { measureChild(view, widthSpec, free); extras += view.getMeasuredHeight(); }
        card.detailHeight(Math.max(0, availableHeight - extras)); super.onMeasure(widthSpec, heightSpec);
    }
    private void render() {
        MediaSessions.Info info = sessions.info(); permission.setVisibility(info.permission() ? GONE : VISIBLE); openPlayer.setEnabled(info.available()); openPlayer.setAlpha(info.available() ? 1 : .32f);
        progress.setEnabled(info.duration() > 0 && info.supports(PlaybackState.ACTION_SEEK_TO)); progress.setVisibility(info.duration() > 0 ? VISIBLE : GONE);
        List<MediaController> list = sessions.players(); playerPicker.setVisibility(list.size() > 1 ? VISIBLE : GONE); playerPicker.setSelected(playersExpanded); playerPicker.setStateDescription(playersExpanded ? "收起播放器列表" : "展开播放器列表");
        players.removeAllViews(); players.setVisibility(playersExpanded && list.size() > 1 ? VISIBLE : GONE);
        if (list.size() > 1) for (MediaController controller : list) { Button choice = Ui.button(getContext(), sessions.playerName(controller), () -> { playersExpanded = false; sessions.select(controller.getSessionToken()); }); choice.setTextSize(10); choice.setTag("media-player-choice"); Ui.select(choice, controller == sessions.controller()); players.addView(choice, new LayoutParams(-1, -2)); }
        removeCallbacks(tick); updateTime(); if (attached && info.playing()) postDelayed(tick, 1000);
    }
    private void updateTime() {
        if (dragging) return; MediaSessions.Info info = sessions.info(); long position = info.positionNow();
        time.setText(info.duration() > 0 ? format(position) : "—:—"); duration.setText(info.duration() > 0 ? format(info.duration()) : "—:—");
        if (info.duration() > 0) { progress.setProgress((int) Math.round(position * 1000d / info.duration())); progress.setStateDescription(format(position) + " / " + format(info.duration())); }
    }
    private static String format(long millis) { long seconds = Math.max(0, millis) / 1000; return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; sessions.observe(update); }
    @Override protected void onDetachedFromWindow() { attached = false; removeCallbacks(tick); sessions.remove(update); super.onDetachedFromWindow(); }
}
