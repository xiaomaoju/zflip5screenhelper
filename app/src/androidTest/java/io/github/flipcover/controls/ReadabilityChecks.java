package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.text.Layout;
import android.util.TypedValue;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Component geometry checks; the instrumentation entry point enforces the emulator-only guard. */
final class ReadabilityChecks {
    private static final String[] CHANGED_PREFS = {"panel_columns", "hand_side", "panel", "favorites", "hub_pins", "panel_density", "panel_labels", "panel_label_size", "panel_tools_position", "panel_brightness", "panel_volume", "panel_media", "panel_media_idle"};
    private final Instrumentation instrumentation;
    private final int expectedRotation;
    private final JSONArray samples = new JSONArray();
    private Activity activity;
    private Context context;
    private Prefs prefs;
    private CoverService owner;
    private AppHubView hub;
    private ControlDetails details;
    private int assertions, hubCloseRequests, hubClearRequests;
    private File directory;

    ReadabilityChecks(Instrumentation instrumentation, int expectedRotation) { this.instrumentation = instrumentation; this.expectedRotation = expectedRotation; }
    private void main(Runnable action) {
        Throwable[] failure = {null};
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof RuntimeException error) throw error;
        if (failure[0] != null) throw new AssertionError("Main-thread check failed", failure[0]);
    }
    private void idle() {
        instrumentation.waitForIdleSync();
        // AppWorkspaceView can request another traversal when its first layout changes the column count.
        CountDownLatch frames = new CountDownLatch(1);
        main(() -> { View root = activity.findViewById(android.R.id.content); root.postOnAnimation(() -> root.postOnAnimation(frames::countDown)); });
        try { require(frames.await(5, TimeUnit.SECONDS), "component receives two animation frames before capture"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError("Interrupted while settling component layout", interrupted); }
        instrumentation.waitForIdleSync();
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    String run() throws Exception {
        prefs = new Prefs(instrumentation.getTargetContext()); Map<String, ?> previous = prefs.data.getAll();
        directory = new File(instrumentation.getTargetContext().getFilesDir(), "readability-raw");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create readability-raw");
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            require(expectedRotation < 0 || activity.getDisplay().getRotation() == expectedRotation, "readability uses the requested physical display rotation");
            prefs.data.edit().putInt("panel_density", 1).putBoolean("panel_labels", true).putInt("panel_label_size", 1).putString("panel_tools_position", "auto")
                .putBoolean("panel_brightness", true).putBoolean("panel_volume", true).putBoolean("panel_media", true).putBoolean("panel_media_idle", false).commit();
            // Resource overrides exercise framework sp conversion, including Android 14 nonlinear scaling.
            // No system font, display, permission, notification or Shizuku setting is changed here.
            // Preserve all eleven default controls, then exercise a longer optional label separately.
            prefs.saveActions("panel", List.of("wifi", "bluetooth", "data", "torch", "dnd", "airplane", "rotation", "screenshot", "lock", "media", "apps", "configure"));
            prefs.saveActions("favorites", List.of("home", "back", "notifications"));
            prefs.saveHubPins(List.of()); CoverApp.catalog(activity).entriesBlocking();
            for (float scale : new float[]{1f, 1.3f, 1.5f, 2f}) {
                main(() -> { Configuration configuration = new Configuration(activity.getResources().getConfiguration()); configuration.fontScale = scale; context = new ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme); });
                for (String hand : new String[]{"left", "right"}) {
                    prefs.data.edit().putString("hand_side", hand).commit();
                    for (int columns : new int[]{3, 4, 5}) {
                        prefs.data.edit().putInt("panel_columns", columns).commit(); mountControls();
                        capture("controls-" + hand + "-" + columns + "-font-" + Math.round(scale * 100));
                        main(() -> checkControls(columns, hand));
                    }
                    mountHub(); capture("hub-" + hand + "-font-" + Math.round(scale * 100)); main(this::checkHub);
                }
                mountMediaDetail(); capture("media-detail-font-" + Math.round(scale * 100)); main(this::checkMediaDetail);
            }
            writeEvidence("PASS");
            return "PASS: readability; " + assertions + " assertions; raw component PNGs in files/readability-raw; no Samsung window-routing proof";
        } catch (Throwable failure) {
            writeEvidence("FAIL: " + failure); throw failure;
        } finally {
            main(() -> { detach(); activity.finish(); });
            SharedPreferences.Editor restore = prefs.data.edit();
            for (String key : CHANGED_PREFS) {
                Object value = previous.get(key);
                if (value instanceof Integer number) restore.putInt(key, number); else if (value instanceof String text) restore.putString(key, text); else if (value instanceof Boolean enabled) restore.putBoolean(key, enabled); else restore.remove(key);
            }
            restore.commit();
        }
    }

    private void detach() {
        if (hub != null) { hub.dispose(); hub = null; }
        if (details != null) { details.close(); details = null; }
        activity.setContentView(new FrameLayout(activity));
        if (owner != null) { require(owner.mediaSessions().observerCount() == 0, "unmounted media surfaces release their observers"); owner = null; }
    }

    private CoverService newOwner() {
        CoverService service = new CoverService(); service.screenContext = context; service.prefs = prefs; service.display = activity.getDisplay(); return service;
    }

    private DockGeometry.Box contentBounds() {
        int width = context.getResources().getDisplayMetrics().widthPixels, height = context.getResources().getDisplayMetrics().heightPixels;
        DockGeometry.Placement placement = DockGeometry.edgeTouch(DockGeometry.resolve(width, height, List.of(), context.getResources().getDisplayMetrics().density, prefs.corner(activity.getDisplay().getRotation()), .46f, .088f, false), width, height);
        return DockGeometry.panelContent(placement, width, height, List.of());
    }

    private void mountControls() {
        main(() -> {
            detach(); owner = newOwner();
            int width = context.getResources().getDisplayMetrics().widthPixels, height = context.getResources().getDisplayMetrics().heightPixels;
            DockGeometry.Placement placement = DockGeometry.edgeTouch(DockGeometry.resolve(width, height, List.of(), context.getResources().getDisplayMetrics().density, prefs.corner(activity.getDisplay().getRotation()), .46f, .088f, false), width, height);
            DockGeometry.Box content = contentBounds(); owner.placement = new DockGeometry.Placement(placement.visual(), placement.touch(), content, placement.edge(), placement.measured());
            View panel = owner.buildPanelContent("controls", new DockGeometry.Box(0, 0, width, height), content.y()); panel.setTag("readability-panel");
            FrameLayout root = new FrameLayout(context); root.setBackgroundColor(Ui.BACKGROUND); root.addView(panel, new FrameLayout.LayoutParams(-1, -1)); activity.setContentView(root);
        }); idle();
    }

    private void checkControls(int columns, String hand) {
        ViewGroup dashboard = tagged("control-dashboard"); int firstRow = 0, markers = 0;
        for (String id : prefs.actions("panel")) {
            ViewGroup tile = dashboard.findViewWithTag("control-" + id); require(tile != null, "configured control is present: " + id);
            if (tile.getTop() == 0) firstRow++;
            contained(tile, (ViewGroup) tile.getParent(), "control " + id);
            ViewGroup face = (ViewGroup) tile.getChildAt(0); View symbol = face.getChildAt(0); TextView marker = (TextView) face.getChildAt(1), label = (TextView) tile.getChildAt(1);
            contained(face, tile, "control face"); contained(symbol, face, "control symbol"); contained(label, tile, "control label");
            require(Math.abs(symbol.getLeft() + symbol.getRight() - face.getWidth()) <= 1 && Math.abs(symbol.getTop() + symbol.getBottom() - face.getHeight()) <= 1, "main icon stays centered independently of unknown-state badge");
            require(face.getBottom() <= label.getTop(), "control icon and label do not overlap"); readable(label, "control label " + id);
            Layout words = label.getLayout(); require(words.getLineCount() <= 2, "control name occupies at most two lines: " + id);
            if (id.equals("configure") || label.getMaxLines() == 1) {
                int visibleCharacters = 0;
                for (int line = 0; line < words.getLineCount(); line++) {
                    int hiddenStart = words.getLineStart(line) + words.getEllipsisStart(line), hiddenEnd = hiddenStart + words.getEllipsisCount(line);
                    for (int index = words.getLineStart(line); index < words.getLineEnd(line); index++) {
                        char character = label.getText().charAt(index);
                        if ((index < hiddenStart || index >= hiddenEnd) && !Character.isWhitespace(character) && character != '\u2026' && character != '\uFEFF') visibleCharacters++;
                    }
                }
                require(visibleCharacters >= (label.getMaxLines() == 1 ? 1 : 2), "compact label retains visible text and the full accessible name");
                require(tile.getContentDescription() != null && tile.getContentDescription().toString().contains(ActionCatalog.label(context, id)), "long optional control retains its full accessible name");
            } else for (int line = 0; line < words.getLineCount(); line++) require(words.getEllipsisCount(line) == 0, "default control name remains complete in at most two lines: " + id);
            float minimum = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9, context.getResources().getDisplayMetrics());
            require(label.getTextSize() + 1 >= minimum, "control labels retain at least 9sp at the selected font scale");
            if (marker.getVisibility() == View.VISIBLE) {
                markers++; readable(marker, "unknown-state marker"); contained(marker, face, "unknown-state marker");
                require(marker.getRight() > face.getWidth() / 2 && marker.getTop() < face.getHeight() / 2, "unknown-state badge remains an independent upper-right overlay");
                require(marker.getTextSize() + 1 >= Ui.dp(context, 8), "unknown-state symbol never shrinks below readable 8dp glyph size");
            }
        }
        require(firstRow == columns, "manual " + columns + " columns survive font scaling and hand layout");
        require(markers > 0, "unknown-state marker checks exercised an actual unconnected control");
        View media = dashboard.findViewWithTag("media-card"), sliders = dashboard.findViewWithTag("control-sliders");
        contained(media, dashboard, "media card"); contained(sliders, dashboard, "sliders"); separate(media, sliders, "media and slider areas");
        for (int i = 0; i < dashboard.getChildCount(); i++) for (int j = i + 1; j < dashboard.getChildCount(); j++) separate(dashboard.getChildAt(i), dashboard.getChildAt(j), "dashboard peer regions");
        float center = (sliders.getLeft() + sliders.getRight()) / 2f;
        require(hand.equals("left") ? center < dashboard.getWidth() / 2f : center > dashboard.getWidth() / 2f, "tool placement follows selected hand");
        checkMediaContents(media);
        ScrollView scroll = find(tagged("readability-panel"), ScrollView.class); require(scroll != null && scroll.getHeight() > 0, "large-font content retains a scrolling viewport");
        // Overflow is supported: require the final media controls to be reachable, not squeezed into one screen.
        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        Rect visible = new Rect();
        if (((MediaCardView) media).condensed()) {
            for (String tag : new String[]{"media-title", "media-artist"}) {
                View words = media.findViewWithTag(tag);
                require(words.getGlobalVisibleRect(visible) && visible.width() == words.getWidth() && visible.height() == words.getHeight(), "condensed media text remains fully reachable by scrolling: " + tag);
            }
            require(media.getGlobalVisibleRect(visible) && visible.width() == media.getWidth() && visible.height() == media.getHeight(), "condensed media entry remains fully reachable by scrolling");
        } else require(media.findViewWithTag("media-transport").getGlobalVisibleRect(visible) && visible.height() > 0, "final media controls remain reachable by scrolling");
        scroll.scrollTo(0, 0);
    }

    private void mountHub() {
        main(() -> {
            detach(); hubCloseRequests = hubClearRequests = 0; hub = new AppHubView(context, prefs, new AppHubView.Listener() {
                public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { hubCloseRequests++; }
                public void clearRecents(List<RecentTasks.Task> tasks) { hubClearRequests++; require(tasks.size() == 1 && tasks.get(0).id() == 17, "clear action preserves the synthetic task identity"); }
            });
            DockGeometry.Box content = contentBounds(); FrameLayout root = new FrameLayout(context); root.setBackgroundColor(Ui.BACKGROUND);
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(content.width(), content.height()); position.leftMargin = content.x(); position.topMargin = content.y(); root.addView(hub, position); activity.setContentView(root);
            hub.recentResult(List.of(new RecentTasks.Task(17, 9, 0, "readability.fixture/.Main", "readability.fixture", false)), "最近任务 · 本地排版样例");
        }); idle();
    }

    private void checkHub() {
        require(hub.expanded(), "application center opens expanded");
        View edit = tagged("hub-edit");
        target(edit, AppLauncherStyle.RAIL_WIDTH, AppLauncherStyle.RAIL_WIDTH, "independent circular sidebar settings");
        require(tagged("hub-tasks") == null && tagged("hub-close") == null, "upper-right buttons are removed");
        TextView search = tagged("hub-search"), sort = tagged("hub-sort"), status = tagged("hub-recent-status");
        require(search.getHeight() >= Ui.dp(context, 30) && sort.getHeight() >= Ui.dp(context, 30), "search and sort retain compact minimum height with natural text growth");
        readable(search, "search field"); readable(sort, "sort action"); readable(status, "recent task status");
        require(status.getHeight() >= Ui.dp(context, 14), "compact recent status retains a readable line with natural font growth");
        View clear = tagged("hub-clear"); target(clear, 22, 34, "compact recent task clear"); separate(status, clear, "recent status and clear action");
        separate(search, sort, "search and sort");
        require(!search.hasFocus(), "opening application center does not summon keyboard");
        AppWorkspaceView grid = tagged("hub-grid");
        if (grid.getAdapter().getCount() > 0) {
            require(grid.getChildCount() > 0, "nonempty application catalog mounts grid cells");
            boolean visibleCell = false; Rect visible = new Rect();
            for (int i = 0; i < grid.getChildCount(); i++) if (grid.getChildAt(i).getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0) visibleCell = true;
            require(visibleCell, "nonempty application catalog exposes at least one cell in the viewport");
        }
        for (int i = 0; i < grid.getChildCount(); i++) { TextView label = find(grid.getChildAt(i), TextView.class); if (label != null) readable(label, "visible application name"); }
        tagged("hub-apps").performClick(); require(!hub.expanded(), "Dock launcher collapses application center"); tagged("hub-apps").performClick(); require(hub.expanded(), "Dock launcher expands application center");
        clear.performClick(); require(hubClearRequests == 1, "clear target invokes only the local fixture callback"); tagged("hub-dismiss-left").performClick(); require(hubCloseRequests == 1, "blank dismissal invokes its callback once");
    }

    private void mountMediaDetail() {
        main(() -> {
            detach(); owner = newOwner(); DetailSheet sheet = new DetailSheet(context, "媒体控制", () -> { });
            details = new ControlDetails(owner, sheet); details.build("media");
            DockGeometry.Box content = contentBounds(); FrameLayout root = new FrameLayout(context); root.setBackgroundColor(Ui.BACKGROUND);
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(content.width(), content.height()); position.leftMargin = content.x(); position.topMargin = content.y(); root.addView(sheet, position); activity.setContentView(root);
        }); idle();
        main(() -> { ((TextView) tagged("media-title")).setText("日落以后 · 很长的中文与 English 媒体标题"); ((TextView) tagged("media-artist")).setText("演示歌手 · 本地排版样例"); }); idle();
    }

    private void checkMediaDetail() {
        ViewGroup card = tagged("detail-card"); contained(card, (ViewGroup) card.getParent(), "detail card");
        require(card.findViewWithTag("detail-close") == null, "media detail has no close button");
        ScrollView scroll = tagged("detail-scroll"); require(scroll.getHeight() > 0, "media detail retains a body viewport");
        separate(card.getChildAt(0), scroll, "fixed media toolbar and scrolling body"); checkMediaContents(tagged("media-expanded"));
    }

    private void checkMediaContents(View media) {
        TextView title = media.findViewWithTag("media-title"), artist = media.findViewWithTag("media-artist");
        readable(title, "media title"); readable(artist, "media artist"); separate(title, artist, "media title and artist");
        View transport = media.findViewWithTag("media-transport");
        if (((MediaCardView) media).condensed()) {
            require(transport.getVisibility() == View.GONE, "condensed media does not expose unavailable transport controls");
            contained(title, (ViewGroup) media, "condensed media title"); contained(artist, (ViewGroup) media, "condensed media artist");
            for (TextView text : new TextView[]{title, artist}) {
                require(text.length() > 0, "condensed entry supplies a nonempty state and action description");
                Layout words = text.getLayout();
                for (int line = 0; line < words.getLineCount(); line++) require(words.getEllipsisCount(line) == 0, "condensed entry preserves its full state and action text");
            }
            int natural = Math.max(Ui.dp(context, 48), title.getHeight() + artist.getHeight() + media.getPaddingTop() + media.getPaddingBottom());
            require(media.getHeight() == natural, "condensed media uses natural text height instead of stretching into empty space");
            target(media, 48, 48, "condensed media entry");
            require(media.isEnabled() && media.getContentDescription() != null && media.getContentDescription().length() > 0, "condensed media entry remains enabled and accessible");
        } else { contained(transport, (ViewGroup) media, "media transport"); separate(artist, transport, "media metadata and transport"); }
    }

    private void readable(TextView text, String name) {
        require(text != null && text.getVisibility() == View.VISIBLE, name + " is visible");
        Layout layout = text.getLayout(); require(layout != null && layout.getLineCount() > 0, name + " has measured text");
        int available = text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom();
        require(available > 0 && layout.getHeight() <= available + 2, name + " line box fits without vertical clipping: " + layout.getHeight() + "/" + available);
        CharSequence content = text.length() == 0 ? text.getHint() : text.getText();
        if (content == null || content.length() == 0) return;
        Rect glyph = new Rect(); String sample = content.toString(); text.getPaint().getTextBounds(sample, 0, sample.length(), glyph);
        require(available + 2 >= glyph.height(), name + " preserves the actual glyph height");
    }

    private void target(View view, int width, int height, String name) {
        require(view != null && view.getVisibility() == View.VISIBLE && view.hasOnClickListeners(), name + " is an interactive target");
        require(view.getWidth() >= Ui.dp(context, width) && view.getHeight() >= Ui.dp(context, height), name + " preserves its " + width + "x" + height + "dp target");
    }

    private void contained(View child, ViewGroup parent, String name) { require(child.getLeft() >= 0 && child.getTop() >= 0 && child.getRight() <= parent.getWidth() && child.getBottom() <= parent.getHeight(), name + " stays in its allocated parent"); }
    private Rect bounds(View view) { int[] location = new int[2]; view.getLocationInWindow(location); return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight()); }
    private void separate(View first, View second, String name) { require(!Rect.intersects(bounds(first), bounds(second)), name + " do not overlap"); }
    @SuppressWarnings("unchecked") private <T extends View> T tagged(String tag) { T view = activity.findViewById(android.R.id.content).findViewWithTag(tag); require(view != null, "component tag exists: " + tag); return view; }
    private <T extends View> T find(View root, Class<T> type) { if (type.isInstance(root)) return type.cast(root); if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { T result = find(group.getChildAt(i), type); if (result != null) return result; } return null; }

    private void capture(String name) throws Exception {
        Bitmap[] bitmap = {null}; main(() -> { View root = activity.findViewById(android.R.id.content); bitmap[0] = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap[0])); });
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { require(bitmap[0].compress(Bitmap.CompressFormat.PNG, 100, output), "raw screenshot writes successfully"); }
        finally { bitmap[0].recycle(); }
        samples.put(new JSONObject().put("file", name + ".png").put("font_scale", context.getResources().getConfiguration().fontScale).put("rotation", activity.getDisplay().getRotation()).put("density_dpi", context.getResources().getDisplayMetrics().densityDpi));
    }

    private void writeEvidence(String status) throws Exception {
        JSONObject evidence = new JSONObject().put("status", status).put("assertions", assertions).put("samples", samples).put("scope", "Raw Android component previews; no device frame, Samsung overlay routing, cross-window blur or frame-timing claim");
        Files.write(new File(directory, "readability.json").toPath(), evidence.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
