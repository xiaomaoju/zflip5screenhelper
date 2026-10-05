package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.util.List;

/** Real mounted launcher, paint-space material coordinates, compact sheets and staged Dock. */
final class LauncherPolishChecks {
    private final Instrumentation test;
    private Activity activity;
    private AppHubView hub;
    private InterfaceCard card;
    private AppWorkspaceView grid;
    private Prefs prefs;
    private LauncherForce force;
    private int assertions;
    LauncherPolishChecks(Instrumentation test) { this.test = test; }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private Object field(Object owner, String name) { try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    String run() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        List<String> apps = CoverApp.catalog(activity).entriesBlocking().stream().map(AppCatalogCache.Entry::id).toList();
        try {
            main(() -> {
                prefs = new Prefs(activity); prefs.data.edit().clear().putBoolean("hub_default_migrated", true).putString("hub_sort", "manual").commit();
                require(apps.size() >= 9, "fixture has independent folder, pinned and recent applications"); prefs.saveHubPins(apps.subList(6, 8));
                hub = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } }); hub.cardHosted();
                String recent = apps.get(8); hub.recentResult(List.of(new RecentTasks.Task(901, 2, 0, recent.substring(4), ActionCatalog.component(recent).getPackageName(), false)), null);
                card = new InterfaceCard(activity, prefs, InterfaceCard.LAUNCHER, hub, hub::preparePanelPush, hub::dispose);
                card.setVisibility(View.INVISIBLE);
                activity.setContentView(card); grid = hub.findViewWithTag("hub-grid"); force = (LauncherForce) field(hub, "launcherForce");
                Bitmap source = Bitmap.createBitmap(720, 748, Bitmap.Config.ARGB_8888); source.eraseColor(0xFF244266); PanelGlassSession glass = new PanelGlassSession(activity, activity.getDisplay()); glass.fixture(source); source.recycle(); card.glass(glass);
            });
            SystemClock.sleep(100);
            main(() -> {
                require(hub.getPaddingLeft() == Ui.dp(activity, 8) && hub.getPaddingRight() == Ui.dp(activity, 8), "both launcher edges reserve 8dp from the plate");
                View catalog = hub.findViewWithTag("hub-catalog"); require(((View) catalog.getParent()).getRight() <= hub.getWidth() - Ui.dp(activity, 8), "local catalog stays inside the plate's reserved gutter");
                LauncherMotionLayout row = hub.findViewWithTag("hub-dock"); AppDockView dock = (AppDockView) ((View) row.getParent()).getParent(); card.setVisibility(View.VISIBLE); dock.stopEntrance(); force.reset();
                require(row.fusionProgress() == 1 && hub.findViewWithTag("hub-apps").isEnabled(), "Dock can finish decorative fusion immediately and remains usable");
                checkDockAnchor(row);
                hub.manualMode(); grid.change(grid.projected().create(apps.subList(0, 6), "紧凑文件夹", 0));
            });
            SystemClock.sleep(150);
            String folder = prefs.workspace().parent(apps.get(0));
            main(() -> {
                grid.settlePage(grid.projected().slot(folder) / grid.capacity(), false); View tile = grid.folderSource(folder), surface = tile.findViewWithTag("folder-surface");
                require(!tile.willNotDraw() && !hub.findViewWithTag("hub-force-header").willNotDraw(), "folder and header participate in hardware draw transforms");
                require(surface.getBackground() instanceof GlassSurface, "folder retains the shared liquid material"); GlassSurface material = (GlassSurface) surface.getBackground(); force.reset(); material.position(); float before = ((float[]) field(material, "previous"))[2];
                grid.settlePage(0, false); grid.pageOffset(0, 0, Ui.dp(activity, 100)); force.reset(); material.position(); float after = ((float[]) field(material, "previous"))[2];
                require(Math.abs(after - before - Ui.dp(activity, 22)) < 2, "folder sampling follows the actual resisted Canvas offset"); grid.settlePage(0, false); grid.launch(folder);
            });
            SystemClock.sleep(350);
            main(() -> {
                DetailSheet sheet = card.findViewWithTag("folder-sheet"); View face = sheet.findViewWithTag("detail-card"); AppFolderGrid members = sheet.findViewWithTag("folder-grid");
                require(!members.willNotDraw(), "opened folder participates in hardware draw transforms");
                require(sheet.findViewWithTag("detail-close") == null && sheet.findViewWithTag("folder-edit") != null, "folder replaces close with one right-hand edit action");
                require(face.getWidth() <= Ui.dp(activity, AppLauncherStyle.FOLDER_SHEET_WIDTH) && members.getChildAt(0).getWidth() <= Ui.dp(activity, 53), "folder backdrop and horizontal member spacing shrink together");
                require(field(sheet, "motion") instanceof DetailSheetMotion, "folder opening reuses the control detail spring");
                require(face.getBackground() instanceof android.graphics.drawable.RippleDrawable ripple && ripple.getDrawable(0) instanceof GlassSurface, "folder styling cannot replace its installed liquid surface");
                sheet.findViewWithTag("folder-edit").performClick();
            });
            SystemClock.sleep(350);
            main(() -> {
                DetailSheet menu = card.findViewWithTag("workspace-actions"); require(menu != null && menu.findViewWithTag("detail-close") == null, "folder actions omit the top-right close button");
                require(menu.content.getChildAt(0).getHeight() <= Ui.dp(activity, AppLauncherStyle.FOLDER_ACTION_HEIGHT + 1), "folder choices use compact 34dp rows");
                long now = SystemClock.uptimeMillis(); MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 2, menu.getHeight() - 2, 0), up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, 2, menu.getHeight() - 2, 0); menu.dispatchTouchEvent(down); menu.dispatchTouchEvent(up); down.recycle(); up.recycle();
            });
            SystemClock.sleep(450);
            main(() -> { require(card.findViewWithTag("workspace-actions") == null && card.findViewWithTag("folder-sheet") == null && hub.expanded(), "outside tap closes the action sheet without reopening a folder or collapsing launcher"); grid.launch(folder); });
            SystemClock.sleep(700); Bitmap image = test.getUiAutomation().takeScreenshot();
            try { java.io.File dir = new java.io.File(test.getTargetContext().getFilesDir(), "ui-smoke"); dir.mkdirs(); try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "launcher-polish-folder.png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); } } finally { image.recycle(); }
            main(() -> hub.recentResult(List.of(), null)); SystemClock.sleep(150);
            main(() -> {
                LauncherMotionLayout row = hub.findViewWithTag("hub-dock"); row.dockEntrance(0); force.reset(); android.graphics.RectF bounds = new android.graphics.RectF(0, 0, row.getWidth(), row.getHeight()); row.materialBounds(bounds);
                require(bounds.left == Ui.dp(activity, AppLauncherStyle.DOCK_TOOL_WIDTH) && bounds.right == row.getWidth() && hub.findViewWithTag("hub-clear") == null, "empty recents reserve no fictional right tool space");
                require(row.visualX() == 0 && bounds.width() == row.getWidth() - Ui.dp(activity, 24), "single-ended contraction keeps the core at its resting position"); row.dockEntrance(1); checkDockAnchor(row);
            });
            main(() -> { hub.dispose(); AppDockView dock = (AppDockView) ((View) hub.findViewWithTag("hub-dock").getParent()).getParent(); require(field(dock, "entrance") == null, "disposed Dock leaves no entrance animator"); });
            return "PASS: launcher-polish; " + assertions + " assertions; native layout, material mapping, outside dismissal and staged Dock; Samsung hand feel pending";
        } finally { main(() -> { if (card != null) card.release(); activity.finish(); }); }
    }
    private void checkDockAnchor(LauncherMotionLayout row) {
        force.reset(); int left = row.getLeft(), width = row.getWidth(); View core = row.getChildAt(1); int coreLeft = core.getLeft();
        android.graphics.drawable.Drawable background = row.getBackground(); int[] visibility = new int[row.getChildCount()];
        for (int i = 0; i < visibility.length; i++) { View child = row.getChildAt(i); visibility[i] = child.getVisibility(); if (child != core) child.setVisibility(View.INVISIBLE); }
        row.setBackground(null); Bitmap image = Bitmap.createBitmap(width, row.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            row.dockEntrance(1); float restingCenter = glyphCenter(row, image);
            for (int ms = 0; ms <= 560; ms += 10) {
                row.dockEntrance(ms / 560f);
                require(row.visualX() == 0 && row.getLeft() == left && row.getWidth() == width && core.getLeft() == coreLeft, "edge reveal cannot translate or relayout the Dock core at " + ms + "ms");
                require(Math.abs(glyphCenter(row, image) - restingCenter) <= 1, "rendered application stays horizontally anchored during tool reveal at " + ms + "ms");
            }
            force.x[LauncherForce.DOCK] = 2; row.dockEntrance(.6f);
            require(row.visualX() == 2 * activity.getResources().getDisplayMetrics().density, "Dock retains shared spring feedback without tool-width compensation");
        } finally {
            image.recycle(); force.reset(); row.dockEntrance(1); row.setBackground(background);
            for (int i = 0; i < visibility.length; i++) row.getChildAt(i).setVisibility(visibility[i]);
        }
    }
    private float glyphCenter(View row, Bitmap image) {
        image.eraseColor(0); row.draw(new android.graphics.Canvas(image)); int first = image.getWidth(), last = -1;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if (android.graphics.Color.alpha(image.getPixel(x, y)) > 128) { first = Math.min(first, x); last = Math.max(last, x); }
        require(last >= first, "Dock core glyph is actually rendered"); return (first + last) / 2f;
    }

}
