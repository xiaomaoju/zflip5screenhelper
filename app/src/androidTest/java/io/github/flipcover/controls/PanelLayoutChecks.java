package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native component checks; callers must enforce the emulator-only instrumentation guard. */
final class PanelLayoutChecks {
    private static final List<String> ACTIONS = List.of("wifi", "bluetooth", "data", "torch", "dnd", "airplane", "rotation", "screenshot", "lock", "media", "apps", "configure");
    private final Instrumentation instrumentation;
    private final JSONArray samples = new JSONArray();
    private Activity activity;
    private Context context;
    private Prefs prefs;
    private CoverService owner;
    private MediaSession fixture;
    private File directory;
    private int assertions, combinations;
    private String currentCase = "initialization";

    private PanelLayoutChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    static String run(Instrumentation instrumentation) throws Exception { return new PanelLayoutChecks(instrumentation).run(); }

    private String run() throws Exception {
        SharedPreferences data = instrumentation.getTargetContext().getSharedPreferences("cover", Context.MODE_PRIVATE);
        Map<String, ?> previous = new HashMap<>(data.getAll());
        prefs = new Prefs(instrumentation.getTargetContext());
        directory = new File(instrumentation.getTargetContext().getFilesDir(), "panel-layout-raw");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create panel-layout-raw");
        try {
            activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            prefs.saveActions("panel", ACTIONS);
            prefs.resetPanelSettings();
            for (float scale : new float[]{1f, 2f}) {
                configureContext(scale);
                for (int mask = 0; mask < 8; mask++) for (String position : new String[]{"auto", "side", "bottom"}) for (String hand : new String[]{"left", "right"}) for (int columns : new int[]{3, 4, 5}) {
                    currentCase = "font=" + scale + ", tools=" + mask + ", position=" + position + ", hand=" + hand + ", columns=" + columns;
                    prefs.applyPanelSettings(prefs.panelSnapshot().put("columns", columns).put("density", 1).put("labels", true).put("labelSize", 1).put("toolsPosition", position)
                        .put("brightness", (mask & 1) != 0).put("volume", (mask & 2) != 0).put("media", (mask & 4) != 0).put("mediaIdle", false));
                    prefs.data.edit().putString("hand_side", hand).commit();
                    mount(false);
                    int selectedMask = mask;
                    main(() -> checkLayout(selectedMask, columns, position, hand, true));
                    combinations++;
                    samples.put(new JSONObject().put("font_scale", scale).put("tool_mask", mask).put("position", position).put("hand", hand).put("columns", columns));
                    if (columns == 5 && hand.equals("right") && position.equals("side") && (mask == 0 || mask == 4 || mask == 7)) capture("matrix-font-" + Math.round(scale * 100) + "-tools-" + mask);
                }
                densityAndLabels();
            }
            contentCounts();
            configureContext(1f);
            previewHasNoActions();
            mediaLifecycle();
            main(this::detach);
            require(combinations == 288, "complete tool/position/hand/column/font matrix exercised");
            evidence("PASS");
            return "PASS: panel-layout; " + assertions + " assertions; " + combinations + " combinations; raw evidence in files/panel-layout-raw; no Samsung window-routing proof";
        } catch (Throwable failure) {
            if (activity != null) try { capture("failure"); } catch (Exception ignored) { }
            evidence("FAIL: " + failure); throw failure;
        } finally {
            try {
                if (activity != null) main(() -> { if (fixture != null) { fixture.release(); fixture = null; } detach(); activity.finish(); });
            } finally {
                instrumentation.getUiAutomation().dropShellPermissionIdentity();
                restore(data, previous);
            }
        }
    }

    private void configureContext(float scale) {
        main(() -> { Configuration configuration = new Configuration(activity.getResources().getConfiguration()); configuration.fontScale = scale; context = new ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme); });
    }

    private void mount(boolean preview) {
        main(() -> {
            detach(); owner = new CoverService(); owner.screenContext = context; owner.prefs = prefs; owner.display = activity.getDisplay();
            int width = context.getResources().getDisplayMetrics().widthPixels, height = context.getResources().getDisplayMetrics().heightPixels;
            DockGeometry.Placement placement = DockGeometry.edgeTouch(DockGeometry.resolve(width, height, List.of(), context.getResources().getDisplayMetrics().density, prefs.corner(activity.getDisplay().getRotation()), .46f, .088f, false), width, height);
            DockGeometry.Box content = DockGeometry.panelContent(placement, width, height, List.of());
            owner.placement = new DockGeometry.Placement(placement.visual(), placement.touch(), content, placement.edge(), placement.measured());
            FrameLayout root = new FrameLayout(context); root.setBackgroundColor(Ui.BACKGROUND);
            if (preview) {
                ScrollView scroll = new ScrollView(context); scroll.setFillViewport(true); scroll.addView(new Panels(owner, true).build("controls"));
                FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(content.width(), content.height()); size.leftMargin = content.x(); size.topMargin = content.y(); root.addView(scroll, size);
            } else root.addView(owner.buildPanelContent("controls", new DockGeometry.Box(0, 0, width, height), content.y()), new FrameLayout.LayoutParams(-1, -1));
            activity.setContentView(root);
        });
        idle();
    }

    private void detach() {
        activity.setContentView(new FrameLayout(activity));
        if (owner != null) { require(owner.mediaSessions().observerCount() == 0, "detached panel releases every media observer"); owner = null; }
    }

    private void checkLayout(int mask, int columns, String position, String hand, boolean labels) {
        ViewGroup dashboard = tagged("control-dashboard"), grid = tagged("control-tile-grid"); ScrollView gridScroll = tagged("control-grid-scroll");
        View brightness = dashboard.findViewWithTag("control-brightness"), volume = dashboard.findViewWithTag("control-volume"), sliders = dashboard.findViewWithTag("control-sliders"), media = dashboard.findViewWithTag("media-card");
        require((brightness != null) == ((mask & 1) != 0), "brightness switch controls actual view construction");
        require((volume != null) == ((mask & 2) != 0), "volume switch controls actual view construction");
        require((media != null) == ((mask & 4) != 0), "media switch controls actual view construction");
        require((sliders != null) == ((mask & 3) != 0), "empty slider wrapper is removed");
        require(owner.mediaSessions().observerCount() == ((mask & 4) != 0 ? 1 : 0), "only mounted real media card subscribes");
        int firstRow = 0, gridBottom = 0, minLeft = Integer.MAX_VALUE, maxRight = 0;
        for (String id : ACTIONS) {
            ViewGroup tile = dashboard.findViewWithTag("control-" + id);
            require(tile != null, "configured action mounted: " + id);
            contained(tile, grid, "tile " + id);
            int minimumWidth = context.getResources().getConfiguration().fontScale > 1.3f || prefs.panelLabelSize() == 2 || prefs.panelDensity() == 2 ? 48 : 28;
            require(tile.getWidth() >= Ui.dp(context, minimumWidth) - 1 && tile.getHeight() >= Ui.dp(context, 48) - 1, "tile retains chosen compact or large-type width: " + id);
            if (tile.getTop() == 0) { firstRow++; minLeft = Math.min(minLeft, gridScroll.getLeft() + tile.getLeft()); maxRight = Math.max(maxRight, gridScroll.getLeft() + tile.getRight()); }
            gridBottom = gridScroll.getBottom();
            ViewGroup face = (ViewGroup) tile.getChildAt(0); TextView label = (TextView) tile.getChildAt(1);
            contained(face, tile, "icon face " + id);
            contained(face.getChildAt(0), face, "icon glyph " + id);
            require(label.getVisibility() == (labels ? View.VISIBLE : View.GONE), "label visibility follows setting: " + id);
            require(tile.getContentDescription() != null && tile.getContentDescription().toString().contains(ActionCatalog.label(context, id)), "full accessible control name survives label setting: " + id);
            if (labels) {
                contained(label, tile, "label " + id); readable(label, "label " + id);
                require(face.getBottom() <= label.getTop(), "label does not overlap icon: " + id);
                require(label.getLayout().getLineCount() <= 2, "long label bounded to two lines: " + id);
                require(visibleCharacters(label) >= Math.min(label.getMaxLines() == 1 ? 1 : 2, label.length()), "label retains visible characters with a full accessible name: " + id);
            }
        }
        require(firstRow == columns, "manual column count is preserved");
        if (mask == 0) require(minLeft == 0 && dashboard.getWidth() - maxRight < columns, "all tools disabled returns full width to grid");
        boolean stacked = (sliders != null && sliders.getTop() >= gridBottom) || (media != null && media.getTop() >= gridBottom);
        if (mask != 0 && position.equals("bottom")) require(stacked, "bottom position places tools below grid");
        View hint = dashboard.findViewWithTag("control-tools-hint");
        require((hint.getVisibility() == View.VISIBLE) == (mask != 0 && position.equals("side") && stacked), "side fallback hint truthfully follows actual placement");
        if (sliders != null && !stacked) require(hand.equals("left") ? sliders.getRight() <= minLeft : sliders.getLeft() >= maxRight, "side tools follow selected hand");
        for (int i = 0; i < dashboard.getChildCount(); i++) {
            View first = dashboard.getChildAt(i); if (first.getVisibility() != View.VISIBLE) continue;
            contained(first, dashboard, "visible dashboard region");
            for (int j = i + 1; j < dashboard.getChildCount(); j++) { View second = dashboard.getChildAt(j); if (second.getVisibility() == View.VISIBLE) require(!Rect.intersects(rect(first), rect(second)), "dashboard regions do not overlap"); }
        }
        if (media != null) {
            MediaCardView card = (MediaCardView) media;
            if (!owner.mediaSessions().info().permission()) require(card.condensed(), "missing permission uses a compact entry");
            require(media.findViewWithTag("media-transport").getVisibility() == (card.condensed() ? View.GONE : View.VISIBLE), "condensed media removes transport area");
            readable((TextView) media.findViewWithTag("media-title"), "media title"); readable((TextView) media.findViewWithTag("media-artist"), "media subtitle");
        }
        ScrollView scroll = find(activity.findViewById(android.R.id.content), ScrollView.class); require(scroll != null, "control content remains scrollable");
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        gridScroll.scrollTo(0, grid.getHeight());
        View tail = hint.getVisibility() == View.VISIBLE ? hint : media != null && media.getBottom() >= gridBottom ? media : sliders != null && sliders.getBottom() >= gridBottom ? sliders : dashboard.findViewWithTag("control-configure");
        reachableTail(tail.getParent() == grid ? gridScroll : scroll, tail, "last control region");
        scroll.scrollTo(0, 0); gridScroll.scrollTo(0, 0);
    }

    private void contentCounts() throws Exception {
        configureContext(2f);
        List<String> many = new ArrayList<>();
        for (ActionCatalog.Action action : ActionCatalog.BUILT_INS) { if (many.size() == 20) break; many.add(action.id()); }
        // The current catalog has 19 built-ins; one real local app supplies a distinct twentieth item.
        if (many.size() < 20) many.add("app:" + new ComponentName(context, MainActivity.class).flattenToString());
        require(many.size() == 20, "long-list fixture contains twenty distinct configured controls");
        try {
            for (int count : new int[]{0, 1, 20}) for (int columns : new int[]{3, 5}) {
                currentCase = "content count=" + count + ", columns=" + columns + ", font=2.0";
                List<String> selected = List.copyOf(many.subList(0, count)); prefs.saveActions("panel", selected);
                prefs.applyPanelSettings(prefs.panelSnapshot().put("columns", columns).put("density", 1).put("labels", true).put("labelSize", 1).put("brightness", false).put("volume", false).put("media", false));
                mount(false);
                main(() -> {
                    View root = activity.findViewById(android.R.id.content); ScrollView scroll = find(root, ScrollView.class);
                    require(scroll != null, "variable control list retains a scroll container");
                    require(owner.mediaSessions().observerCount() == 0, "variable control list with tools off has no media observer");
                    ViewGroup dashboard = root.findViewWithTag("control-dashboard");
                    if (count == 0) {
                        require(dashboard == null, "zero controls and zero tools omit dashboard");
                        TextView add = findText(root, "添加控制按钮"); require(add != null, "empty control center offers an add action");
                        reachableTail(scroll, add, "empty-list add action");
                        return;
                    }
                    require(dashboard != null, "nonempty control list mounts dashboard");
                    ViewGroup grid = root.findViewWithTag("control-tile-grid"); ScrollView gridScroll = root.findViewWithTag("control-grid-scroll");
                    int firstRow = 0, firstRowRight = 0;
                    for (String id : selected) {
                        ViewGroup tile = dashboard.findViewWithTag("control-" + id); require(tile != null, "variable list preserves configured action " + id);
                        contained(tile, grid, "variable-list tile " + id);
                        int minimumWidth = context.getResources().getConfiguration().fontScale > 1.3f || prefs.panelLabelSize() == 2 || prefs.panelDensity() == 2 ? 48 : 28;
                        require(tile.getHeight() >= Ui.dp(context, 48) - 1 && tile.getWidth() >= Ui.dp(context, minimumWidth) - 1, "variable list keeps chosen compact or large-type size");
                        if (tile.getTop() == 0) { firstRow++; firstRowRight = Math.max(firstRowRight, tile.getRight()); }
                        TextView label = (TextView) tile.getChildAt(1); contained(label, tile, "variable-list label"); readable(label, "variable-list label");
                        require(gridScroll.getHeight() == tile.getHeight() * 4, "quantity never changes the four-row viewport");
                    }
                    require(firstRow == Math.min(count, columns), "variable count preserves manual columns");
                    View first = dashboard.findViewWithTag("control-" + selected.get(0));
                    require(Math.abs(first.getWidth() - dashboard.getWidth() / columns) <= 1, "one-control case still uses the requested column width");
                    if (count >= columns) require(dashboard.getWidth() - firstRowRight < columns, "long control list fills all available grid width");
                    for (int index = 0; index < selected.size(); index++) for (int other = index + 1; other < selected.size(); other++) require(!Rect.intersects(rect(dashboard.findViewWithTag("control-" + selected.get(index))), rect(dashboard.findViewWithTag("control-" + selected.get(other)))), "variable-list controls never overlap");
                    scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                    gridScroll.scrollTo(0, grid.getHeight());
                    View last = dashboard.findViewWithTag("control-" + selected.get(selected.size() - 1)); last.requestRectangleOnScreen(new Rect(0, 0, last.getWidth(), last.getHeight()), true); reachableTail(gridScroll, last, "final variable-list control");
                    scroll.scrollTo(0, 0); gridScroll.scrollTo(0, 0);
                });
                if (columns == 5) capture("content-count-" + count + "-font-200");
            }
        } finally { prefs.saveActions("panel", ACTIONS); }
    }

    private void reachableTail(ScrollView scroll, View tail, String name) {
        Rect visible = new Rect(), viewport = new Rect();
        require(scroll.getGlobalVisibleRect(viewport) && viewport.height() > 0, name + " has a visible viewport");
        require(tail.getGlobalVisibleRect(visible) && visible.width() == tail.getWidth(), name + " is fully visible horizontally");
        if (tail.getHeight() <= viewport.height()) require(visible.height() == tail.getHeight(), name + " is fully reachable vertically");
        else {
            int[] position = new int[2]; tail.getLocationOnScreen(position);
            require(visible.height() > 0 && visible.bottom == position[1] + tail.getHeight(), name + " reaches its bottom edge when taller than the viewport");
        }
    }

    private void densityAndLabels() throws Exception {
        prefs.data.edit().putString("hand_side", "right").commit();
        int previousHeight = 0;
        for (int density : new int[]{0, 1, 2}) {
            currentCase = "density=" + density + ", font=" + context.getResources().getConfiguration().fontScale;
            prefs.applyPanelSettings(prefs.panelSnapshot().put("columns", 5).put("density", density).put("labelSize", density).put("labels", true).put("brightness", false).put("volume", false).put("media", false));
            mount(false); main(() -> checkLayout(0, 5, "auto", "right", true));
            int[] shown = {0}; main(() -> shown[0] = tagged("control-wifi").getHeight());
            require(shown[0] >= previousHeight, "larger density does not reduce tile height"); previousHeight = shown[0];
            prefs.applyPanelSettings(prefs.panelSnapshot().put("labels", false)); mount(false);
            main(() -> { checkLayout(0, 5, "auto", "right", false); require(tagged("control-wifi").getHeight() <= shown[0], "hidden label reclaims its row space"); });
        }
    }

    private void previewHasNoActions() throws Exception {
        currentCase = "inert preview";
        main(() -> {
            Panels refreshed = new Panels(owner); View contents = refreshed.build("controls"); refreshed.updateStates(); refreshed.updateStates();
            View rotation = contents.findViewWithTag("control-rotation");
            require(rotation.getContentDescription().toString().contains(ActionCatalog.label(context, "rotation")), "repeated state updates retain the full accessible action name");
        });
        prefs.panelPreset("standard"); prefs.applyPanelSettings(prefs.panelSnapshot().put("brightness", true).put("volume", true).put("media", true));
        mount(true);
        AudioManager audio = context.getSystemService(AudioManager.class); int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int rotation = Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, -1), brightness = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
        Map<String, ?> before = new HashMap<>(prefs.data.getAll());
        main(() -> {
            require(owner.mediaSessions().observerCount() == 0, "preview does not subscribe to media");
            View root = activity.findViewById(android.R.id.content);
            for (String id : ACTIONS) { View tile = tagged("control-" + id); tile.performClick(); tile.performLongClick(); require(activity.findViewById(android.R.id.content) == root, "preview action leaves surface in place: " + id); }
            for (String tag : new String[]{"media-card", "media-play", "media-previous", "media-next", "control-brightness", "control-volume"}) { View view = tagged(tag); view.performClick(); view.performLongClick(); }
            require(!tagged("control-brightness").isEnabled() && !tagged("control-volume").isEnabled(), "preview sliders are inert");
            require(((TextView) tagged("media-title")).getText().toString().contains("示意"), "preview labels its media as illustrative");
            require(owner.mediaSessions().observerCount() == 0, "preview interactions create no subscriptions");
        });
        idle();
        require(before.equals(prefs.data.getAll()), "preview click and long-click preserve settings");
        require(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == volume, "preview does not change actual media volume");
        require(Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, -1) == rotation && Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1) == brightness, "preview does not change system rotation or brightness");
        capture("inert-preview");
    }

    private void mediaLifecycle() throws Exception {
        currentCase = "media lifecycle";
        instrumentation.getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
        prefs.applyPanelSettings(prefs.panelSnapshot().put("columns", 4).put("toolsPosition", "bottom").put("brightness", false).put("volume", false).put("media", true).put("mediaIdle", true));
        mount(false);
        MediaCardView[] originalCard = {null}; int[] compactHeight = {0};
        main(() -> {
            owner.mediaSessions().refresh(); require(owner.mediaSessions().info().permission() && !owner.mediaSessions().info().available(), "isolated fixture begins with authorized empty media state");
            originalCard[0] = tagged("media-card"); require(originalCard[0].condensed(), "idle option condenses no-session media"); compactHeight[0] = originalCard[0].getHeight();
            fixture = new MediaSession(activity, "panel-layout-fixture");
            fixture.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            fixture.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "播放状态变化 · 本地验证").putString(MediaMetadata.METADATA_KEY_ARTIST, "测试播放器").build());
            fixture.setPlaybackState(playback(PlaybackState.STATE_PLAYING)); fixture.setActive(true);
        });
        awaitMedia(true, true);
        main(() -> {
            require(tagged("media-card") == originalCard[0], "session arrival updates existing card"); require(!originalCard[0].condensed(), "playing media expands card");
            require(originalCard[0].getHeight() > compactHeight[0], "active card receives room for playback controls");
            require(tagged("media-transport").getVisibility() == View.VISIBLE, "playing card exposes transport");
            require(tagged("media-play").getContentDescription().toString().equals("暂停"), "playing state uses pause action");
            require(owner.mediaSessions().observerCount() == 1, "session update retains one media observer");
            fixture.setPlaybackState(playback(PlaybackState.STATE_PAUSED));
        });
        awaitMedia(true, false);
        main(() -> { require(!originalCard[0].condensed(), "paused available session stays expanded"); require(tagged("media-play").getContentDescription().toString().equals("播放"), "paused state uses play action"); fixture.release(); fixture = null; });
        awaitMedia(false, false);
        main(() -> { require(tagged("media-card") == originalCard[0] && originalCard[0].condensed(), "session release condenses existing card"); require(owner.mediaSessions().observerCount() == 1, "visible idle entry keeps one event subscription"); });
        capture("media-idle-entry");
        prefs.applyPanelSettings(prefs.panelSnapshot().put("media", false)); mount(false);
        main(() -> { require(activity.findViewById(android.R.id.content).findViewWithTag("media-card") == null, "disabled media module is removed"); require(owner.mediaSessions().observerCount() == 0, "all tools disabled leaves no media observer"); });
        instrumentation.getUiAutomation().dropShellPermissionIdentity();
    }

    private static PlaybackState playback(int state) { return new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE).setState(state, 0, state == PlaybackState.STATE_PLAYING ? 1f : 0f).build(); }
    private void awaitMedia(boolean available, boolean playing) {
        long deadline = SystemClock.uptimeMillis() + 3000;
        boolean[] matches = {false};
        do { main(() -> { MediaSessions.Info info = owner.mediaSessions().info(); matches[0] = info.available() == available && info.playing() == playing; }); idle(); } while (!matches[0] && SystemClock.uptimeMillis() < deadline);
        require(matches[0], "media fixture reaches requested availability/playback state");
    }

    private int visibleCharacters(TextView label) {
        Layout text = label.getLayout(); int count = 0;
        for (int line = 0; line < text.getLineCount(); line++) {
            int start = text.getLineStart(line), hidden = start + text.getEllipsisStart(line), hiddenEnd = hidden + text.getEllipsisCount(line);
            for (int at = start; at < text.getLineEnd(line); at++) { char value = label.getText().charAt(at); if ((at < hidden || at >= hiddenEnd) && !Character.isWhitespace(value) && value != '\u2026' && value != '\uFEFF') count++; }
        }
        return count;
    }
    private void readable(TextView text, String name) { Layout words = text.getLayout(); require(words != null && words.getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom(), name + " fits its text box vertically"); }
    private void contained(View child, ViewGroup parent, String name) { require(child.getLeft() >= 0 && child.getTop() >= 0 && child.getRight() <= parent.getWidth() && child.getBottom() <= parent.getHeight(), name + " stays in its parent"); }
    private static Rect rect(View view) { return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()); }
    @SuppressWarnings("unchecked") private <T extends View> T tagged(String tag) { T view = activity.findViewById(android.R.id.content).findViewWithTag(tag); require(view != null, "view exists: " + tag); return view; }
    private static <T extends View> T find(View root, Class<T> type) { if (type.isInstance(root)) return type.cast(root); if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { T found = find(group.getChildAt(i), type); if (found != null) return found; } return null; }
    private static TextView findText(View root, String text) { if (root instanceof TextView view && view.getText().toString().equals(text)) return view; if (root instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) { TextView found = findText(group.getChildAt(index), text); if (found != null) return found; } return null; }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private void main(Runnable action) {
        Throwable[] failure = {null}; instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] instanceof Error error) throw error; if (failure[0] instanceof RuntimeException error) throw error; if (failure[0] != null) throw new AssertionError(failure[0]);
    }
    private void idle() {
        instrumentation.waitForIdleSync(); CountDownLatch frames = new CountDownLatch(1);
        main(() -> activity.findViewById(android.R.id.content).postOnAnimation(() -> activity.findViewById(android.R.id.content).postOnAnimation(frames::countDown)));
        try { require(frames.await(5, TimeUnit.SECONDS), "panel receives two layout frames"); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        instrumentation.waitForIdleSync();
    }
    private void capture(String name) throws Exception {
        Bitmap[] bitmap = {null}; main(() -> { View root = activity.findViewById(android.R.id.content); bitmap[0] = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap[0])); });
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { require(bitmap[0].compress(Bitmap.CompressFormat.PNG, 100, output), "raw component image saved"); } finally { bitmap[0].recycle(); }
    }
    private void evidence(String status) throws Exception {
        JSONObject evidence = new JSONObject().put("status", status).put("assertions", assertions).put("combinations", combinations).put("current_case", currentCase).put("samples", samples).put("scope", "Native components only; no device frame, Samsung overlay routing, cross-window blur or performance claim");
        Files.write(new File(directory, "panel-layout.json").toPath(), evidence.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private static void restore(SharedPreferences data, Map<String, ?> original) {
        SharedPreferences.Editor update = data.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof Boolean bool) update.putBoolean(key, bool); else if (value instanceof Integer number) update.putInt(key, number); else if (value instanceof Long number) update.putLong(key, number); else if (value instanceof Float number) update.putFloat(key, number); else if (value instanceof String text) update.putString(key, text);
            else if (value instanceof Set<?> set) { Set<String> strings = new HashSet<>(); for (Object item : set) strings.add((String) item); update.putStringSet(key, strings); } else throw new AssertionError("Unknown preference type: " + key);
        }
        if (!update.commit()) throw new AssertionError("Unable to restore panel-layout preferences");
    }
}
