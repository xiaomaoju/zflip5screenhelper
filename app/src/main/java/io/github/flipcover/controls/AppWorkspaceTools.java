package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** In-window folder sheets and explicit organization commands; all layout writes go through the workspace transaction. */
final class AppWorkspaceTools {
    private final AppHubView hub;
    private final AppWorkspaceView grid;
    private final Prefs prefs;
    private final Context context;
    private DetailSheet sheet;
    private AppFolderGrid members;
    private String opened;
    private boolean external;
    AppWorkspaceTools(AppHubView hub, AppWorkspaceView grid, Prefs prefs) { this.hub = hub; this.grid = grid; this.prefs = prefs; context = hub.getContext(); }
    String opened() { return opened; }
    boolean back() { if (sheet == null) return false; if (external) grid.cancelInteraction(); sheet.close(); return true; }
    private void toast(String text) { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show(); }
    private void attempt(Runnable action) { try { action.run(); } catch (IllegalArgumentException error) { toast(error.getMessage()); } }
    private boolean writable() { if (prefs.workspaceLocked()) { toast("桌面布局已锁定，请在排序菜单解除"); return false; } return true; }
    void close() {
        external = false; if (members != null) { members.cancel(); members = null; }
        DetailSheet old = sheet; sheet = null; opened = null;
        if (old != null && old.getParent() instanceof android.view.ViewGroup parent) { context.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(old.getWindowToken(), 0); parent.removeView(old); }
        hub.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }
    private DetailSheet show(String name, Runnable back) {
        close(); grid.cancelInteraction();
        if (!(hub.getParent() instanceof FrameLayout host)) throw new IllegalArgumentException("应用中心尚未挂载");
        final DetailSheet[] ref = new DetailSheet[1];
        DetailSheet current = new DetailSheet(context, name, () -> { if (sheet != ref[0]) return; close(); if (back != null) back.run(); }); ref[0] = current; sheet = current;
        current.handleWindowBack();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(hub.getWidth(), hub.getHeight()); params.leftMargin = hub.getLeft() - host.getPaddingLeft(); params.topMargin = hub.getTop() - host.getPaddingTop(); host.addView(current, params);
        hub.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); current.enter(null); return current;
    }
    void resize() {
        if (external) { close(); return; }
        if (sheet == null || !(sheet.getParent() instanceof FrameLayout host)) return;
        if (members != null) members.cancel();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) sheet.getLayoutParams(); params.width = hub.getWidth(); params.height = hub.getHeight(); params.leftMargin = hub.getLeft() - host.getPaddingLeft(); params.topMargin = hub.getTop() - host.getPaddingTop(); sheet.setLayoutParams(params);
    }
    /** Context actions share the selected display's existing safe host, never an anchored subwindow. */
    Actions actions(String title, Runnable back) { return new Actions(show(title, back)); }
    final class Actions {
        private final DetailSheet owner;
        Actions(DetailSheet owner) { this.owner = owner; owner.setTag("workspace-actions"); owner.title.setSingleLine(); owner.title.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        void add(String label, Runnable action) { add(label, true, action); }
        void add(String label, boolean enabled, Runnable action) {
            TextView row = Ui.text(context, label, 12, Ui.TEXT); row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setMinHeight(Ui.dp(context, 48)); row.setPadding(Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6));
            row.setBackground(Ui.ripple(context, android.graphics.Color.TRANSPARENT, 8)); row.setEnabled(enabled); row.setAlpha(enabled ? 1 : .4f); row.setFocusable(true);
            row.setOnClickListener(v -> { if (sheet != owner) return; close(); attempt(action); }); owner.content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    void changed() { if (opened != null && !external) { String id = opened; if (grid.layoutSnapshot().folder(id) == null) close(); else folder(id); } }
    void icons(Set<String> ids) { if (sheet != null) hub.updateIcons(sheet, ids); }
    void folder(String id) {
        AppWorkspaceLayout.Folder folder = grid.layoutSnapshot().folder(id); if (folder == null) return;
        DetailSheet current = show(folder.name() + " · " + folder.members().size() + "/9", null); opened = id; current.setTag("folder-sheet");
        current.title.setSingleLine(); current.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        View more = Ui.iconButton(context, R.drawable.ic_ms_edit, "文件夹管理", () -> menu(sheet.title, id)); current.folderStyle(folder.name(), folder.members().size(), more);
        members = new AppFolderGrid(context, folder.members(), !prefs.workspaceLocked(), app -> { View cell = hub.shortcut(app, hub.label(app), AppLauncherStyle.FOLDER_MEMBER_ICON, AppLauncherStyle.LABEL_SP, () -> hub.launchApplication(app)); cell.setMinimumHeight(Ui.dp(context, AppLauncherStyle.FOLDER_MEMBER_HEIGHT)); return cell; }, new AppFolderGrid.Listener() {
            public void launch(String app) { hub.launchApplication(app); }
            public void menu(View anchor, String app) { memberMenu(anchor, id, app); }
            public void reorder(List<String> order) { attempt(() -> grid.change(grid.projected().reorder(id, order))); }
            public boolean outside(float x, float y) { View card = current.findViewWithTag("detail-card"); Rect bounds = new Rect(); card.getGlobalVisibleRect(bounds); return !bounds.contains((int) x, (int) y); }
            public boolean external(String app, float x, float y) {
                if (!grid.editable()) return false;
                int[] origin = new int[2]; grid.getLocationOnScreen(origin); external = true; current.setAlpha(0); grid.externalDrag(app, x - origin[0], y - origin[1]); return grid.dragging();
            }
            public void forward(MotionEvent event) { int[] origin = new int[2]; grid.getLocationOnScreen(origin); MotionEvent copy = MotionEvent.obtain(event); copy.setLocation(event.getRawX() - origin[0], event.getRawY() - origin[1]); grid.dispatchTouchEvent(copy); copy.recycle(); }
            public void endExternal() { close(); }
        }); current.content.addView(members, new LinearLayout.LayoutParams(-1, -2));
        current.title.setTooltipText("长按成员排序；拖出卡片移到桌面");
        current.title.setContentDescription(folder.name() + "，" + folder.members().size() + "个应用，长按成员排序，拖出卡片移到桌面");
    }
    void menu(View anchor, String id) {
        if (id.isEmpty()) { organize(null); return; }
        AppWorkspaceLayout.Folder folder = grid.layoutSnapshot().folder(id); if (folder == null) return;
        Actions menu = actions(folder.name(), () -> folder(id)); menu.add("打开文件夹", () -> folder(id));
        menu.add("重命名", !prefs.workspaceLocked(), () -> text("文件夹名称", folder.name(), value -> grid.change(grid.projected().rename(id, value)), () -> folder(id)));
        menu.add("添加应用 · " + folder.members().size() + "/9", !prefs.workspaceLocked() && folder.members().size() < 9, () -> organize(id));
        menu.add("按名称整理成员", !prefs.workspaceLocked(), () -> { List<String> order = new ArrayList<>(folder.members()); order.sort((a, b) -> hub.label(a).compareToIgnoreCase(hub.label(b))); grid.change(grid.projected().reorder(id, order)); });
        menu.add("强调色", !prefs.workspaceLocked(), () -> { Actions colors = actions("强调色", () -> menu(anchor, id)); String[] labels = {"默认", "蓝", "绿", "粉", "紫", "金"}; for (int i = 0; i < labels.length; i++) { int color = i; colors.add(labels[i] + (folder.color() == i ? " ✓" : ""), () -> grid.change(grid.projected().color(id, color))); } });
        menu.add("移到其他页", !prefs.workspaceLocked(), () -> choosePage(List.of(id)));
        menu.add("解散文件夹（保留应用）", !prefs.workspaceLocked(), () -> preview("解散后恢复 " + folder.members().size() + " 个应用", grid.projected().dissolve(id)));
        menu.add("撤销上次整理", grid.canUndo(), grid::undo);
    }
    private void memberMenu(View anchor, String folder, String app) {
        Actions menu = actions(hub.label(app), () -> folder(folder));
        menu.add("启动 " + hub.label(app), () -> hub.launchApplication(app));
        menu.add("移出到桌面", !prefs.workspaceLocked(), () -> grid.change(grid.projected().extract(app, grid.projected().slot(folder))));
        menu.add("移到其他文件夹", !prefs.workspaceLocked(), () -> chooseFolder(List.of(app), folder));
        List<String> order = grid.layoutSnapshot().folder(folder).members(); int index = order.indexOf(app);
        for (int direction : new int[]{-1, 1}) menu.add(direction < 0 ? "向前移动" : "向后移动", !prefs.workspaceLocked() && index + direction >= 0 && index + direction < order.size(), () -> { List<String> next = new ArrayList<>(order); java.util.Collections.swap(next, index, index + direction); grid.change(grid.projected().reorder(folder, next)); folder(folder); });
    }
    void alias(String id) { text("应用别名（清空恢复原名）", prefs.workspaceAlias(id), value -> { prefs.workspaceAlias(id, value); hub.workspaceOptionsChanged(); }, null); }
    private void text(String name, String initial, Consumer<String> save, Runnable after) {
        if (!writable()) return; DetailSheet current = show(name, after); EditText input = new EditText(context); input.setTextColor(Ui.TEXT); input.setSingleLine(); input.setText(initial); input.setSelectAllOnFocus(true); input.setTag("workspace-name"); current.content.addView(input);
        current.footer("保存", () -> attempt(() -> { save.accept(input.getText().toString()); close(); if (after != null) after.run(); }));
    }
    private void preview(String title, AppWorkspaceLayout next) {
        if (!writable()) return; AppWorkspaceLayout base = grid.layoutSnapshot(); DetailSheet current = show(title, null);
        current.content.addView(Ui.text(context, "整理后共 " + next.apps().size() + " 个应用、" + next.folders().size() + " 个文件夹、" + next.pages(grid.capacity()) + " 页。完成后可撤销一次。", 12, Ui.TEXT));
        for (String id : next.folders().keySet()) if (base.folder(id) == null) { AppFolderTile tile = new AppFolderTile(context); tile.bind(next.folder(id), hub::bindIcon, 0); current.content.addView(tile, new LinearLayout.LayoutParams(Ui.dp(context, 120), Ui.dp(context, 120))); }
        current.footer("完成整理", () -> attempt(() -> { if (!base.equals(grid.layoutSnapshot())) throw new IllegalArgumentException("布局已改变，请重新整理"); grid.change(next); close(); }));
    }
    void organize(String destination) {
        if (!writable()) return; DetailSheet current = show(destination == null ? "多选整理 / 新建文件夹" : "添加到文件夹", null); current.setTag("workspace-organize");
        AppWorkspaceLayout base = grid.layoutSnapshot(); AppWorkspaceLayout.Folder folder = base.folder(destination); Set<String> selected = new LinkedHashSet<>();
        TextView count = Ui.text(context, "已选择 0 项", 12, Ui.TEXT); current.content.addView(count);
        EditText search = new EditText(context); search.setSingleLine(); search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.MUTED); search.setHint("搜索应用、别名或拼音"); current.content.addView(search);
        List<AppCatalogCache.Entry> all = new ArrayList<>(CoverApp.catalog(context).snapshot()); List<String> pinned = prefs.hubPins(); all.removeIf(app -> pinned.contains(app.id())); List<AppCatalogCache.Entry> visible = new ArrayList<>(all); AppSearchIndex index = new AppSearchIndex(); org.json.JSONObject aliases = prefs.workspaceAliases();
        ListView list = new ListView(context); list.setTag("workspace-selection"); BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return visible.size(); } public Object getItem(int p) { return visible.get(p); } public long getItemId(int p) { return p; }
            public View getView(int p, View reusable, android.view.ViewGroup parent) {
                AppCatalogCache.Entry app = visible.get(p); String id = app.id(), owner = base.parent(id); boolean existing = destination != null && destination.equals(owner);
                CheckBox row = reusable instanceof CheckBox ? (CheckBox) reusable : new CheckBox(context); row.setTextColor(Ui.TEXT); row.setTextSize(12); row.setMinimumHeight(Ui.dp(context, 48));
                row.setOnCheckedChangeListener(null); row.setText(hub.label(id) + (owner == null ? "" : " · " + base.folder(owner).name())); row.setChecked(existing || selected.contains(id)); row.setEnabled(!existing);
                row.setOnCheckedChangeListener((button, checked) -> { if (checked && folder != null && selected.size() >= 9 - folder.members().size()) { button.setChecked(false); toast("最多9个，请先取消一个选择"); return; } if (checked) selected.add(id); else selected.remove(id); count.setText("已选择 " + selected.size() + " 项" + (folder == null ? "" : " · 剩余 " + (9 - folder.members().size() - selected.size()))); }); return row;
            }
        }; list.setAdapter(adapter); current.content.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(context, 210)));
        search.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int c, int f) { } public void afterTextChanged(android.text.Editable e) { } public void onTextChanged(CharSequence s, int a, int before, int count) { visible.clear(); String query = s.toString().trim().toLowerCase(java.util.Locale.ROOT); for (AppCatalogCache.Entry app : all) if (index.matches(app, aliases.optString(app.id(), ""), query)) visible.add(app); adapter.notifyDataSetChanged(); } });
        current.footer(destination == null ? "选择操作…" : "预览加入", () -> attempt(() -> {
            if (!base.equals(grid.layoutSnapshot())) throw new IllegalArgumentException("布局已改变，请重新打开整理");
            if (selected.isEmpty()) throw new IllegalArgumentException("请先选择应用"); List<String> ids = List.copyOf(selected);
            if (destination != null) { AppWorkspaceLayout next = grid.projected(); for (String app : ids) next = next.merge(app, destination); preview("加入 " + ids.size() + " 个应用", next); }
            else { PopupMenu actions = new PopupMenu(context, current.title);
                actions.getMenu().add("新建文件夹（2–9个）").setEnabled(ids.size() >= 2 && ids.size() <= 9).setOnMenuItemClickListener(item -> { attempt(() -> preview("新建文件夹", grid.projected().create(ids, "文件夹", grid.page() * grid.capacity()))); return true; });
                actions.getMenu().add("加入已有文件夹").setOnMenuItemClickListener(item -> { chooseFolder(ids, null); return true; });
                actions.getMenu().add("移到其他页").setOnMenuItemClickListener(item -> { choosePage(ids); return true; }); actions.show(); }
        }));
    }
    private void chooseFolder(List<String> apps, String excluded) {
        DetailSheet current = show("选择目标文件夹", null); boolean found = false;
        for (String id : grid.layoutSnapshot().ordered()) { AppWorkspaceLayout.Folder folder = grid.layoutSnapshot().folder(id); if (folder == null || id.equals(excluded)) continue; found = true;
            int added = 0; for (String app : apps) if (!folder.members().contains(app)) added++;
            View button = Ui.button(context, folder.name() + " · " + folder.members().size() + "/9", () -> attempt(() -> { AppWorkspaceLayout next = grid.projected(); for (String app : apps) if (!id.equals(next.parent(app))) next = next.merge(app, id); preview("加入 " + folder.name(), next); })); button.setEnabled(folder.members().size() + added <= 9); current.content.addView(button);
        }
        if (!found) current.content.addView(Ui.text(context, "没有其他文件夹，请先新建", 12, Ui.MUTED));
    }
    void locate(String app) { String parent = grid.layoutSnapshot().parent(app); if (parent != null) { hub.manualMode(); grid.settlePage(grid.projected().slot(parent) / grid.capacity(), false); folder(parent); } else hub.locate(app); }
    private void pageList(DetailSheet current, int count, java.util.function.BiFunction<Integer, View, View> bind) {
        ListView list = new ListView(context); list.setAdapter(new BaseAdapter() { public int getCount() { return count; } public Object getItem(int position) { return position; } public long getItemId(int position) { return position; } public View getView(int position, View reusable, android.view.ViewGroup parent) { return bind.apply(position, reusable); } });
        current.content.addView(list, new LinearLayout.LayoutParams(-1, Ui.dp(context, 220)));
        current.onContentHeight(height -> { int target = Math.max(Ui.dp(context, 48), Math.min(Ui.dp(context, 220), height)); if (list.getLayoutParams().height != target) { list.getLayoutParams().height = target; } });
    }
    private void choosePage(List<String> ids) {
        DetailSheet current = show("移到哪一页", null); int pages = grid.projected().pages(grid.capacity());
        pageList(current, Math.min(pages + 1, (AppWorkspaceLayout.MAX_SLOTS + grid.capacity() - 1) / grid.capacity()), (page, reusable) -> {
            android.widget.Button button = reusable instanceof android.widget.Button ? (android.widget.Button) reusable : Ui.button(context, "", () -> { }); button.setText("第 " + (page + 1) + " 页" + (page == pages ? "（新页）" : "")); button.setOnClickListener(v -> attempt(() -> {
            AppWorkspaceLayout next = grid.projected(); int target = page * grid.capacity(); for (String id : ids) { next = next.parent(id) == null ? next.move(id, target, false) : next.extract(id, target); target = Math.min(AppWorkspaceLayout.MAX_SLOTS - 1, next.slot(id) + next.span(id)); } preview("移动 " + ids.size() + " 项到第 " + (page + 1) + " 页", next);
            })); return button;
        });
    }
    void pages() {
        DetailSheet current = show("页面管理", null); AppWorkspaceLayout layout = grid.projected(); int count = layout.pages(grid.capacity());
        int[] counts = new int[count]; for (String id : layout.ordered()) counts[layout.slot(id) / grid.capacity()]++;
        pageList(current, count, (page, reusable) -> {
            LinearLayout row; if (reusable instanceof LinearLayout existing) row = existing; else { row = Ui.row(context); TextView name = Ui.text(context, "", 12, Ui.TEXT); name.setSingleLine(); name.setEllipsize(android.text.TextUtils.TruncateAt.END); row.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(Ui.button(context, "查看", () -> { })); row.addView(Ui.button(context, "…", () -> { })); }
            TextView label = (TextView) row.getChildAt(0); label.setText("第 " + (page + 1) + " 页 · " + counts[page] + " 项"); row.getChildAt(1).setOnClickListener(v -> { close(); hub.manualMode(); grid.settlePage(page, true); });
            row.getChildAt(2).setOnClickListener(v -> { PopupMenu menu = new PopupMenu(context, label);
                menu.getMenu().add("向前一页移动").setEnabled(!prefs.workspaceLocked() && page > 0).setOnMenuItemClickListener(item -> { attempt(() -> { grid.change(grid.projected().swapPages(page, page - 1)); pages(); }); return true; });
                menu.getMenu().add("向后一页移动").setEnabled(!prefs.workspaceLocked() && page + 1 < count).setOnMenuItemClickListener(item -> { attempt(() -> { grid.change(grid.projected().swapPages(page, page + 1)); pages(); }); return true; });
                menu.getMenu().add("删除空页").setEnabled(!prefs.workspaceLocked()).setOnMenuItemClickListener(item -> { attempt(() -> { grid.change(grid.projected().removeEmptyPage(page)); pages(); }); return true; }); menu.show(); }); return row;
        });
    }
}
