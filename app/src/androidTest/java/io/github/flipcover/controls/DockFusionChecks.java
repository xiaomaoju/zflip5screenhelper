package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import com.kyant.backdrop.catalog.components.LiquidTensionGeometry;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Actual GPU connectivity, fixed glyph pixels, one timer and one fusion clock. Emulator only. */
final class DockFusionChecks {
    private final Instrumentation test;
    private Activity activity;
    private AppDockView dock;
    private LauncherMotionLayout row;
    private FrameLayout root;
    private PanelGlassSession glass;
    private int assertions, toggles;
    DockFusionChecks(Instrumentation test) { this.test = test; }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private Object field(Object owner, String name) { try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    String run() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking();
        try {
            CountDownLatch sampled = new CountDownLatch(1); boolean[] waiting = {false}, started = {false};
            main(() -> {
                require(apps.size() >= 3, "fixture has three real app identities");
                Prefs prefs = new Prefs(activity); prefs.data.edit().clear().commit(); prefs.saveHubPins(List.of(apps.get(0).id(), apps.get(1).id()));
                dock = new AppDockView(activity, prefs, new AppDockView.Listener() {
                    public View application(String id, int width, String prefix, Runnable action) { View icon = new View(activity); icon.setBackgroundColor(0xFF426D98); icon.setOnClickListener(v -> action.run()); return icon; }
                    public void launch(String id) { } public void openTask(RecentTasks.Task task) { } public void clear(List<RecentTasks.Task> tasks) { }
                    public void toggleApps() { toggles++; } public void editPinned() { } public void refresh() { } public void close() { } public void menu() { }
                });
                dock.launcherForce(new LauncherForce()); dock.glassHosted();
                AppCatalogCache.Entry recent = apps.get(2); dock.data(List.of(new RecentTasks.Task(801, 2, 0, recent.id().substring(4), recent.packageName(), false)), true, false, true, true, false);
                root = new FrameLayout(activity); root.setBackgroundColor(Color.BLACK); root.setVisibility(View.INVISIBLE); root.addView(dock, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER)); activity.setContentView(root);
                Bitmap texture = Bitmap.createBitmap(720, 748, Bitmap.Config.ARGB_8888); texture.eraseColor(Color.WHITE); glass = new PanelGlassSession(activity, activity.getDisplay()); glass.fixture(texture); texture.recycle();
                row = dock.findViewWithTag("hub-dock"); glass.bind(dock, GlassSurface.Role.STACK); glass.bind(row, GlassSurface.Role.LAUNCHER_DOCK);
            });
            SystemClock.sleep(150);
            main(() -> {
                root.setVisibility(View.VISIBLE); dock.reveal();
                dock.postDelayed(() -> waiting[0] = (boolean) field(dock, "fusionPending") && field(dock, "entrance") == null && row.fusionProgress() == 0, LauncherForce.ENTRY_ESTIMATE_MS + AppDockView.FUSION_WAIT_MS - 30);
                dock.postDelayed(() -> { Object clock = field(dock, "entrance"); started[0] = clock instanceof android.animation.ValueAnimator; if (started[0]) ((android.animation.ValueAnimator) clock).pause(); sampled.countDown(); }, LauncherForce.ENTRY_ESTIMATE_MS + AppDockView.FUSION_WAIT_MS + 60);
            });
            require(sampled.await(6, TimeUnit.SECONDS) && waiting[0] && started[0], "fusion starts only after fixed entry estimate plus 300ms, with no waiting animator");
            main(() -> row.dockEntrance(0)); Bitmap separate = capture("separate"); Rect region = region();
            int initialComponents;
            try { initialComponents = components(separate, region); require(initialComponents == 3, "GPU shows exactly three independent glass components: " + initialComponents); } finally { separate.recycle(); }
            Rect[] initialGlyphs = new Rect[2], finalGlyphs = new Rect[2]; int[] padding = new int[8], size = new int[4], position = new int[3];
            main(() -> {
                View left = row.findViewWithTag("hub-apps"), right = row.findViewWithTag("hub-clear"); initialGlyphs[0] = glyph(false); initialGlyphs[1] = glyph(true);
                padding[0] = left.getPaddingLeft(); padding[1] = left.getPaddingTop(); padding[2] = left.getPaddingRight(); padding[3] = left.getPaddingBottom(); padding[4] = right.getPaddingLeft(); padding[5] = right.getPaddingTop(); padding[6] = right.getPaddingRight(); padding[7] = right.getPaddingBottom();
                size[0] = left.getWidth(); size[1] = left.getHeight(); size[2] = right.getWidth(); size[3] = right.getHeight(); position[0] = row.getLeft(); position[1] = row.getWidth(); position[2] = row.getChildAt(1).getLeft();
                require(left.isEnabled() && right.isEnabled(), "independent tools remain immediately usable");
                require(row.toolSlideX(false) < 0 && row.toolSlideX(true) > 0, "initial tools lie outside their final centers and will travel inward");
                for (int frame = 0; frame <= 56; frame++) {
                    row.dockEntrance(frame / 56f); Rect l = glyph(false), r = glyph(true);
                    require(Math.abs(l.width() - initialGlyphs[0].width()) <= 1 && l.height() == initialGlyphs[0].height() && Math.abs(r.width() - initialGlyphs[1].width()) <= 1 && r.height() == initialGlyphs[1].height(), "painted glyph size stays fixed within one subpixel raster edge throughout fusion at frame " + frame + ": left=" + l + "/" + initialGlyphs[0] + ", right=" + r + "/" + initialGlyphs[1]);
                    require(row.getLeft() == position[0] && row.getWidth() == position[1] && row.getChildAt(1).getLeft() == position[2], "fusion cannot move or relayout the application core");
                }
                finalGlyphs[0] = glyph(false); finalGlyphs[1] = glyph(true);
                require(finalGlyphs[0].left > initialGlyphs[0].left && finalGlyphs[1].left < initialGlyphs[1].left, "actual glyph pixels move inward from both sides");
                require(left.getWidth() == size[0] && left.getHeight() == size[1] && right.getWidth() == size[2] && right.getHeight() == size[3] && left.getPaddingLeft() == padding[0] && left.getPaddingTop() == padding[1] && left.getPaddingRight() == padding[2] && left.getPaddingBottom() == padding[3] && right.getPaddingLeft() == padding[4] && right.getPaddingTop() == padding[5] && right.getPaddingRight() == padding[6] && right.getPaddingBottom() == padding[7], "final tool dimensions and every padding edge are unchanged");
                row.dockEntrance(.45f);
            });
            Bitmap neck = capture("neck"); try { require(components(neck, region) == 1, "GPU draws continuous liquid necks joining both sides"); } finally { neck.recycle(); }
            main(() -> row.dockEntrance(.999f)); Bitmap beforeRest = capture("before-rest");
            main(() -> { ((android.animation.ValueAnimator) field(dock, "entrance")).end(); require(field(dock, "entrance") == null && !(boolean) field(dock, "fusionPending"), "completed fusion releases its clock and pending timer"); }); Bitmap merged = capture("merged");
            try { require(components(merged, region) == 1, "GPU resting Dock is one capsule"); require(maxDifference(beforeRest, merged, region) <= 8, "three-to-one contour handoff has no material flash"); } finally { beforeRest.recycle(); merged.recycle(); }
            main(() -> {
                glass.close(); Bitmap pattern = Bitmap.createBitmap(720, 748, Bitmap.Config.ARGB_8888);
                for (int y = 0; y < pattern.getHeight(); y++) for (int x = 0; x < pattern.getWidth(); x++) pattern.setPixel(x, y, Color.rgb(x < 360 ? 240 : 12, (x + y) * 255 / 1466, ((x / 64 + y / 64) & 1) == 0 ? 240 : 12));
                glass = new PanelGlassSession(activity, activity.getDisplay()); glass.fixture(pattern); pattern.recycle(); glass.bind(dock, GlassSurface.Role.STACK); glass.bind(row, GlassSurface.Role.LAUNCHER_DOCK); row.dockEntrance(.999f);
            }); Bitmap texturedBefore = capture("textured-before-rest"); main(() -> row.dockEntrance(1)); Bitmap texturedAfter = capture("textured-merged");
            try { int difference = maxDifference(texturedBefore, texturedAfter, region); require(difference <= 8, "high-contrast fused material has no final sampling jump: " + difference); } finally { texturedBefore.recycle(); texturedAfter.recycle(); }
            main(() -> {
                dock.data(List.of(), true, false, true, true, false); dock.requestLayout();
            }); SystemClock.sleep(150);
            main(() -> {
                row.dockEntrance(0); LiquidTensionGeometry shape = new LiquidTensionGeometry(); dock.geometry(shape); require(shape.getCount() == 2 && row.findViewWithTag("hub-clear") == null, "empty recents leave only the left circle and compact core");
                row.findViewWithTag("hub-apps").performClick(); require(toggles == 1 && row.fusionProgress() == 1 && field(dock, "entrance") == null, "tool click finishes decoration immediately and executes once");
                row.dockEntrance(0); dock.setVisibility(View.INVISIBLE); dock.stopEntrance(); require(!(boolean) field(dock, "fusionPending") && row.fusionProgress() == 1, "hidden Dock has no residual fusion work");
                root.removeView(dock); require(field(dock, "entrance") == null && !(boolean) field(dock, "fusionPending"), "unmounted Dock cannot retain its timer or clock");
            });
            return "PASS: dock-fusion; " + assertions + " assertions; GPU three-to-one connectivity, fixed icon pixels and padding, fixed core, timer and lifecycle";
        } finally { main(() -> { if (dock != null) dock.stopEntrance(); if (glass != null) glass.close(); activity.finish(); }); }
    }
    private Bitmap capture(String name) throws Exception {
        SystemClock.sleep(160); Bitmap image = test.getUiAutomation().takeScreenshot(); java.io.File dir = new java.io.File(test.getTargetContext().getFilesDir(), "ui-smoke"); dir.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "dock-fusion-" + name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } return image;
    }
    private Rect region() { Rect bounds = new Rect(); main(() -> require(dock.getGlobalVisibleRect(bounds), "GPU Dock region is actually visible")); return bounds; }
    private Rect glyph(boolean right) {
        View tool = right ? row.findViewWithTag("hub-clear") : row.getChildAt(0); int margin = Ui.dp(activity, 18); android.graphics.drawable.Drawable background = row.getBackground(); int[] visibility = new int[row.getChildCount()];
        for (int i = 0; i < visibility.length; i++) { View child = row.getChildAt(i); visibility[i] = child.getVisibility(); if (child != tool) child.setVisibility(View.INVISIBLE); }
        row.setBackground(null); Bitmap image = Bitmap.createBitmap(row.getWidth() + margin * 2, row.getHeight(), Bitmap.Config.ARGB_8888);
        try { Canvas canvas = new Canvas(image); canvas.translate(margin, 0); row.draw(canvas); Rect out = new Rect(image.getWidth(), image.getHeight(), 0, 0);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if (Color.alpha(image.getPixel(x, y)) > 128) { out.left = Math.min(out.left, x); out.top = Math.min(out.top, y); out.right = Math.max(out.right, x + 1); out.bottom = Math.max(out.bottom, y + 1); }
            require(!out.isEmpty(), "actual tool glyph was painted"); return out;
        } finally { image.recycle(); row.setBackground(background); for (int i = 0; i < visibility.length; i++) row.getChildAt(i).setVisibility(visibility[i]); }
    }
    private int components(Bitmap image, Rect region) {
        int w = region.width(), h = region.height(), count = 0; boolean[] seen = new boolean[w * h]; int[] queue = new int[w * h];
        for (int i = 0; i < seen.length; i++) { if (seen[i] || Color.green(image.getPixel(region.left + i % w, region.top + i / w)) < 90) continue;
            int head = 0, tail = 1; queue[0] = i; seen[i] = true;
            while (head < tail) { int at = queue[head++], x = at % w, y = at / w;
                int[] neighbors = {x > 0 ? at - 1 : -1, x < w - 1 ? at + 1 : -1, y > 0 ? at - w : -1, y < h - 1 ? at + w : -1};
                for (int next : neighbors) if (next >= 0 && !seen[next] && Color.green(image.getPixel(region.left + next % w, region.top + next / w)) >= 90) { seen[next] = true; queue[tail++] = next; }
            }
            if (tail > Ui.dp(activity, 8) * Ui.dp(activity, 8)) count++;
        } return count;
    }
    private int maxDifference(Bitmap a, Bitmap b, Rect region) { int largest = 0; for (int y = region.top; y < region.bottom; y++) for (int x = region.left; x < region.right; x++) { int first = a.getPixel(x, y), second = b.getPixel(x, y); largest = Math.max(largest, Math.max(Math.abs(Color.red(first) - Color.red(second)), Math.max(Math.abs(Color.green(first) - Color.green(second)), Math.abs(Color.blue(first) - Color.blue(second))))); } return largest; }
}
