package io.github.flipcover.controls;

import android.graphics.Bitmap;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact cover/metadata/transport card; enlarged variant shares the same real session. */
final class MediaCardView extends LinearLayout {
    private final CoverService owner;
    private final MediaSessions sessions;
    private final boolean compact;
    private final boolean preview;
    private final ImageView artwork;
    private final TextView title, artist;
    private final ImageButton previous, play, next;
    private final LinearLayout artRow, transport;
    private final View spacer;
    private boolean condensed;
    private int detailHeight = Integer.MAX_VALUE;
    private final Runnable update = this::render;
    private Bitmap displayedArtwork;
    private boolean attached;
    MediaCardView(CoverService owner, boolean compact) { this(owner, compact, false); }
    MediaCardView(CoverService owner, boolean compact, boolean preview) {
        super(owner.screenContext); this.owner = owner; this.compact = compact; this.preview = preview; sessions = preview ? null : owner.mediaSessions();
        setOrientation(VERTICAL); setTag(compact ? "media-card" : "media-expanded"); int padding = Ui.dp(getContext(), compact ? 5 : 2); setPadding(padding, padding, padding, padding);
        RuntimeVisuals.surface(this, compact ? 0xED252A33 : android.graphics.Color.TRANSPARENT, 16); if (compact) { setFocusable(!preview); setOnClickListener(v -> openMedia()); setOnLongClickListener(v -> { openMedia(); return true; }); }
        artRow = Ui.row(getContext()); artRow.setGravity(compact ? Gravity.TOP : Gravity.CENTER);
        artwork = new ImageView(getContext()); artwork.setTag("media-artwork"); artwork.setScaleType(ImageView.ScaleType.CENTER_CROP); artwork.setBackground(Ui.background(getContext(), 0xFF2A2C30, compact ? 7 : 12)); artwork.setClipToOutline(true); artwork.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        artRow.addView(artwork, new LayoutParams(Ui.dp(getContext(), compact ? 26 : 48), Ui.dp(getContext(), compact ? 26 : 48)));
        if (compact) {
            View space = new View(getContext()); artRow.addView(space, new LayoutParams(0, 1, 1));
            ImageButton output = button(R.drawable.ic_ms_volume_up, "选择媒体输出", () -> owner.showDetails("volume", this)); output.setTag("media-output"); artRow.addView(output, new LayoutParams(Ui.dp(getContext(), 24), Ui.dp(getContext(), 24)));
        }
        addView(artRow, new LayoutParams(-1, -2));
        title = Ui.heading(getContext(), "", compact ? 10 : 11); title.setTag("media-title"); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END); title.setPadding(0, Ui.dp(getContext(), 4), 0, 0); addView(title, new LayoutParams(-1, -2));
        artist = Ui.text(getContext(), "", compact ? 8 : 9, Ui.MUTED); artist.setTag("media-artist"); artist.setMaxLines(1); artist.setEllipsize(TextUtils.TruncateAt.END); artist.setPadding(0, Ui.dp(getContext(), 2), 0, 0); addView(artist, new LayoutParams(-1, -2));
        spacer = new View(getContext()); addView(spacer, new LayoutParams(1, Ui.dp(getContext(), 3), compact ? 1 : 0));
        transport = Ui.row(getContext()); transport.setTag("media-transport");
        previous = button(R.drawable.ic_ms_skip_previous, "上一首", () -> command(KeyEvent.KEYCODE_MEDIA_PREVIOUS)); previous.setTag("media-previous");
        play = button(R.drawable.ic_ms_play_arrow, "播放", () -> command(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)); play.setTag("media-play");
        next = button(R.drawable.ic_ms_skip_next, "下一首", () -> command(KeyEvent.KEYCODE_MEDIA_NEXT)); next.setTag("media-next");
        for (ImageButton button : new ImageButton[]{previous, play, next}) transport.addView(button, new LayoutParams(0, Ui.dp(getContext(), compact ? 28 : 36), 1));
        addView(transport, new LayoutParams(-1, -2)); if (!compact) setMinimumWidth(Ui.dp(getContext(), 188)); render();
    }
    private ImageButton button(int icon, String description, Runnable action) {
        ImageButton button = RuntimeVisuals.button(getContext(), icon, description, () -> { if (!preview) action.run(); }); int padding = Ui.dp(getContext(), compact ? 4 : 8); button.setPadding(padding, padding, padding, padding);
        button.setOnLongClickListener(v -> { if (compact) openMedia(); return true; }); return button;
    }
    private void openMedia() { if (!preview) { if (sessions.info().permission()) owner.showDetails("media", this); else owner.openSettings("permissions"); } }
    private void command(int key) { if (!sessions.command(key)) owner.message("播放器暂不支持此操作"); }
    private void state(ImageButton button, boolean enabled) { button.setEnabled(enabled); button.setAlpha(enabled ? 1 : .32f); }
    private void render() {
        if (preview) {
            title.setText("媒体卡示意"); artist.setText("不读取播放状态"); artwork.setImageDrawable(Ui.icon(getContext(), R.drawable.ic_ms_music_note, Ui.MUTED));
            state(previous, false); state(play, false); state(next, false); setContentDescription("媒体卡示意，不读取播放状态"); return;
        }
        MediaSessions.Info info = sessions.info();
        boolean nextCondensed = compact && (!info.permission() || owner.prefs.panelMediaIdle() && !info.available());
        if (condensed != nextCondensed) {
            condensed = nextCondensed; artRow.setVisibility(condensed ? GONE : VISIBLE); transport.setVisibility(condensed ? GONE : VISIBLE); spacer.setVisibility(condensed ? GONE : VISIBLE);
            title.setMaxLines(condensed ? 2 : 1); artist.setMaxLines(condensed ? 2 : 1); setMinimumHeight(condensed ? Ui.dp(getContext(), 48) : 0); requestLayout();
        }
        String subtitle = compact && !info.permission() ? "轻点前往授权" : info.artist();
        if (!TextUtils.equals(title.getText(), info.title())) title.setText(info.title()); if (!TextUtils.equals(artist.getText(), subtitle)) artist.setText(subtitle);
        Bitmap art = info.artwork(); if (art != displayedArtwork || artwork.getDrawable() == null) { displayedArtwork = art; if (art != null && !art.isRecycled()) artwork.setImageBitmap(art); else artwork.setImageDrawable(Ui.icon(getContext(), R.drawable.ic_ms_music_note, Ui.MUTED)); }
        play.setImageDrawable(Ui.icon(getContext(), info.playing() ? R.drawable.ic_ms_pause : R.drawable.ic_ms_play_arrow, Ui.TEXT)); play.setContentDescription(info.playing() ? "暂停" : "播放");
        state(play, info.supports(info.playing() ? PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE : PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE)); state(previous, info.supports(PlaybackState.ACTION_SKIP_TO_PREVIOUS)); state(next, info.supports(PlaybackState.ACTION_SKIP_TO_NEXT));
        setContentDescription(info.title() + "，" + subtitle + (compact && info.permission() ? "，轻点媒体详情" : ""));
    }
    void beforeTransport(View view) { addView(view, indexOfChild(transport), new LayoutParams(-1, -2)); }
    void detailHeight(int available) { detailHeight = available; }
    boolean condensed() { return condensed; }
    int minimumCardHeight(int width) {
        int words = MeasureSpec.makeMeasureSpec(Math.max(1, width - getPaddingLeft() - getPaddingRight()), MeasureSpec.EXACTLY), free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        title.measure(words, free); artist.measure(words, free);
        int wordsHeight = getPaddingTop() + getPaddingBottom() + title.getMeasuredHeight() + artist.getMeasuredHeight();
        return condensed ? Math.max(Ui.dp(getContext(), 48), wordsHeight) : wordsHeight + Ui.dp(getContext(), 26 + 3 + 28);
    }
    @Override protected void onMeasure(int width, int height) {
        if (compact) { int available = MeasureSpec.getSize(height), cover = Math.max(Ui.dp(getContext(), 26), Math.min(Ui.dp(getContext(), 40), available - minimumCardHeight(MeasureSpec.getSize(width)) + Ui.dp(getContext(), 26))); artwork.getLayoutParams().width = artwork.getLayoutParams().height = cover; }
        else {
            int inner = Math.max(1, MeasureSpec.getSize(width) - getPaddingLeft() - getPaddingRight()), fixed = getPaddingTop() + getPaddingBottom();
            for (int i = 0; i < getChildCount(); i++) { View child = getChildAt(i); if (child == artRow || child.getVisibility() == GONE) continue; measureChild(child, MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)); fixed += child.getMeasuredHeight(); }
            int cover = Math.min(inner, Math.max(Ui.dp(getContext(), 44), Math.min(Ui.dp(getContext(), 104), detailHeight - fixed)));
            artwork.getLayoutParams().width = artwork.getLayoutParams().height = cover;
        }
        super.onMeasure(width, height);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (!preview) { attached = true; sessions.observe(update); } }
    @Override protected void onDetachedFromWindow() { if (attached) sessions.remove(update); attached = false; displayedArtwork = null; artwork.setImageDrawable(null); super.onDetachedFromWindow(); }
}
