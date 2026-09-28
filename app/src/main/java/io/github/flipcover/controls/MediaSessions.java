package io.github.flipcover.controls;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/** Visible media surfaces share one bounded, memory-only set of platform callbacks. */
final class MediaSessions {
    record Info(String title, String artist, Bitmap artwork, boolean playing, long actions, long duration, long position, long updated, float speed, boolean available, boolean permission) {
        boolean supports(long action) { return available && (actions & action) != 0; }
        long positionNow() { return positionAt(position, updated, speed, playing, duration, SystemClock.elapsedRealtime()); }
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaSessionManager manager;
    private final ComponentName listener;
    private final List<Runnable> observers = new ArrayList<>();
    private final List<MediaController> sessions = new ArrayList<>();
    private MediaSession.Token selected;
    private boolean manualSelection, registered, permission;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::replace;
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { notifyObservers(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { choose(); notifyObservers(); }
        @Override public void onSessionDestroyed() { refresh(); }
    };
    MediaSessions(Context context) { this.context = context.getApplicationContext(); manager = this.context.getSystemService(MediaSessionManager.class); listener = new ComponentName(this.context, CoverNotifications.class); }
    void observe(Runnable observer) {
        if (observers.contains(observer)) return; observers.add(observer);
        if (observers.size() == 1) {
            refresh();
        } else observer.run();
    }
    void remove(Runnable observer) { observers.remove(observer); if (observers.isEmpty()) stop(); }
    void refresh() {
        if (observers.isEmpty()) return;
        try { if (!registered) { manager.addOnActiveSessionsChangedListener(changed, listener, main); registered = true; } List<MediaController> active = manager.getActiveSessions(listener); permission = true; replace(active); }
        catch (RuntimeException e) { permission = false; replace(List.of()); }
    }
    private void replace(List<MediaController> active) {
        if (observers.isEmpty()) return;
        for (MediaController controller : sessions) controller.unregisterCallback(callback); sessions.clear();
        if (active != null) for (MediaController controller : active) { if (sessions.size() >= 8) break; sessions.add(controller); controller.registerCallback(callback, main); }
        choose(); notifyObservers();
    }
    private void choose() {
        MediaController current = controller(); if (current == null) manualSelection = false;
        if (current != null && (manualSelection || isPlaying(current.getPlaybackState()))) return;
        for (MediaController item : sessions) if (isPlaying(item.getPlaybackState())) { selected = item.getSessionToken(); return; }
        if (current == null) { selected = sessions.isEmpty() ? null : sessions.get(0).getSessionToken(); manualSelection = false; }
    }
    private void notifyObservers() { for (Runnable observer : List.copyOf(observers)) observer.run(); }
    MediaController controller() { for (MediaController controller : sessions) if (controller.getSessionToken().equals(selected)) return controller; return null; }
    List<MediaController> players() { return List.copyOf(sessions); }
    void select(MediaSession.Token token) { if (sessions.stream().noneMatch(item -> item.getSessionToken().equals(token))) return; selected = token; manualSelection = true; notifyObservers(); }
    String playerName(MediaController controller) { try { return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(controller.getPackageName(), 0)).toString(); } catch (Exception ignored) { return controller.getPackageName(); } }
    Info info() {
        MediaController controller = controller();
        if (controller == null) return new Info(permission ? "暂无媒体" : "媒体未授权", permission ? "先打开播放器" : "长按前往授权", null, false, 0, 0, 0, 0, 0, false, permission);
        MediaMetadata metadata = controller.getMetadata(); PlaybackState state = controller.getPlaybackState();
        String title = text(metadata, MediaMetadata.METADATA_KEY_TITLE), artist = text(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        if (title.isEmpty()) title = text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title.isEmpty()) title = playerName(controller); if (artist.isEmpty()) artist = playerName(controller);
        Bitmap artwork = null;
        if (metadata != null) { artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART); if (artwork == null) artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART); if (artwork == null) artwork = metadata.getDescription().getIconBitmap(); }
        return new Info(title, artist, artwork, isPlaying(state), state == null ? 0 : state.getActions(), metadata == null ? 0 : Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)), state == null ? 0 : Math.max(0, state.getPosition()), state == null ? 0 : state.getLastPositionUpdateTime(), state == null ? 0 : state.getPlaybackSpeed(), true, permission);
    }
    private static String text(MediaMetadata metadata, String key) { if (metadata == null) return ""; String value = metadata.getString(key); return value == null ? "" : value.substring(0, Math.min(512, value.length())); }
    static boolean isPlaying(PlaybackState state) { return state != null && state.getState() == PlaybackState.STATE_PLAYING; }
    static long positionAt(long position, long updated, float speed, boolean playing, long duration, long now) {
        double value = Math.max(0, position); if (playing && updated > 0 && now > updated && Float.isFinite(speed)) value += (now - updated) * (double) speed;
        return Math.max(0, Math.min(duration > 0 ? duration : Long.MAX_VALUE, (long) value));
    }
    boolean command(int key) {
        MediaController current = controller(); if (current == null) return false; Info info = info();
        long needed = key == KeyEvent.KEYCODE_MEDIA_PREVIOUS ? PlaybackState.ACTION_SKIP_TO_PREVIOUS : key == KeyEvent.KEYCODE_MEDIA_NEXT ? PlaybackState.ACTION_SKIP_TO_NEXT : info.playing() ? PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE : PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_PAUSE;
        if (!info.supports(needed)) return false;
        try { MediaController.TransportControls transport = current.getTransportControls(); if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) transport.skipToPrevious(); else if (key == KeyEvent.KEYCODE_MEDIA_NEXT) transport.skipToNext(); else if (info.playing()) transport.pause(); else transport.play(); return true; } catch (RuntimeException e) { refresh(); return false; }
    }
    boolean seek(long position) {
        MediaController current = controller(); Info info = info(); if (current == null || !info.supports(PlaybackState.ACTION_SEEK_TO) || info.duration() <= 0) return false;
        try { current.getTransportControls().seekTo(Math.max(0, Math.min(position, info.duration()))); return true; } catch (RuntimeException e) { refresh(); return false; }
    }
    private void stop() {
        if (registered) { manager.removeOnActiveSessionsChangedListener(changed); registered = false; }
        for (MediaController controller : sessions) controller.unregisterCallback(callback); sessions.clear(); selected = null; manualSelection = false; permission = false;
    }
    int observerCount() { return observers.size(); }
}
