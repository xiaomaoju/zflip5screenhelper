package io.github.flipcover.controls;

import android.content.ClipData;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.function.Consumer;

/** One in-panel draft. Selected and candidate grids share a local, validated drag session. */
final class ControlEditorView extends FrameLayout {
    private final Prefs prefs;
    private final Runnable finish;
    private final Consumer<String> registerTile;
    private final ArrayList<String> original, draft;
    private ArrayList<String> undo;
    private Bundle candidateState;
    private final TextView title, count;
    private final Button undoButton, expand;
    private final Workspace workspace;
    private final ScrollView selectedScroll;
    private final ControlEditGrid selected;
    private ControlCandidatesView candidates;
    private DetailSheet modal;
    private boolean expanded;
    private DragSession drag;
    private float dragX, dragY;
    private int target = -1, scrollStep;
    private boolean removeTarget;
    private record DragSession(ControlEditorView owner, String id, boolean selected, View origin) { }
    private final Runnable edgeScroll = new Runnable() {
        @Override public void run() {
            if (drag == null || scrollStep == 0 || !isAttachedToWindow()) return;
            int before = selectedScroll.getScrollY(); selectedScroll.scrollBy(0, scrollStep); locateDrag();
            if (before != selectedScroll.getScrollY()) postOnAnimation(this);
        }
    };

    ControlEditorView(Context c, Prefs prefs, Bundle restored, Consumer<String> registerTile, Runnable finish) {
        super(c); this.prefs = prefs; this.finish = finish; this.registerTile = registerTile; setTag("control-editor"); setBackgroundColor(SettingsUi.BACKGROUND); setFocusableInTouchMode(true); setHapticFeedbackEnabled(prefs.haptics());
        original = restored == null ? new ArrayList<>(prefs.actions("panel")) : restored.getStringArrayList("original"); draft = restored == null ? new ArrayList<>(original) : restored.getStringArrayList("draft"); undo = restored == null ? null : restored.getStringArrayList("undo");
        candidateState = restored == null ? new Bundle() : bundle(restored, "candidates"); expanded = restored == null || restored.getBoolean("expanded", true);
        LinearLayout page = SettingsUi.column(c); addView(page, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = SettingsUi.row(c); header.addView(SettingsUi.iconButton(c, R.drawable.ic_ms_arrow_back, "返回", this::back));
        LinearLayout heading = SettingsUi.column(c); title = SettingsUi.heading(c, "自定义", 18); title.setAccessibilityHeading(true); title.setSingleLine(); title.setEllipsize(android.text.TextUtils.TruncateAt.END); heading.addView(title); count = Ui.text(c, "", 12, SettingsUi.MUTED); count.setSingleLine(); heading.addView(count); if (getResources().getConfiguration().fontScale > 1.15f) { title.setText("编辑"); count.setVisibility(GONE); } header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        undoButton = SettingsUi.button(c, "撤销", this::undo); undoButton.setTag("control-editor-undo"); undoButton.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0); undoButton.setBackground(Ui.ripple(c, 0, 12)); header.addView(undoButton);
        Button done = SettingsUi.button(c, "完成", this::save); done.setTag("control-editor-done"); done.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0); done.setBackground(Ui.ripple(c, 0, 12)); header.addView(done); page.addView(header);
        selectedScroll = new ScrollView(c); selectedScroll.setTag("control-selected-scroll"); selectedScroll.setFillViewport(false); selectedScroll.setClipToPadding(false);
        selected = new ControlEditGrid(c, prefs.panelColumns(), this::options, (id, view) -> startDrag(id, true, view)); selected.items(draft); selectedScroll.addView(selected);
        expand = SettingsUi.button(c, "+ 添加按钮", this::showPicker); expand.setTag("control-editor-add");
        workspace = new Workspace(c); workspace.addView(selectedScroll); page.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1)); attachCandidates();
        if (restored != null) selectedScroll.post(() -> selectedScroll.scrollTo(0, restored.getInt("selected-y")));
        setOnDragListener((view, event) -> dragEvent(event)); update(); setAccessibilityPaneTitle("控制中心自定义"); requestFocus();
    }
    private static Bundle bundle(Bundle state, String key) { Bundle value = state.getBundle(key); return value == null ? new Bundle() : new Bundle(value); }
    Bundle snapshot() {
        Bundle state = new Bundle(); state.putStringArrayList("original", new ArrayList<>(original)); state.putStringArrayList("draft", new ArrayList<>(draft)); if (undo != null) state.putStringArrayList("undo", new ArrayList<>(undo)); state.putBundle("candidates", candidates == null ? candidateState : candidates.snapshot()); state.putBoolean("expanded", expanded); state.putInt("selected-y", selectedScroll.getScrollY()); return state;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) { if (event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) { if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) back(); return true; } return super.dispatchKeyEvent(event); }
    void back() {
        cancelDrag(); if (modal != null) { dismissModal(); return; }
        if (candidates != null && candidates.closeSearch()) return;
        if (expanded) { collapseCandidates(); return; }
        if (draft.equals(original)) { finish.run(); return; }
        DetailSheet sheet = sheet("放弃这次编辑？"); sheet.content.addView(SettingsUi.settingRow(getContext(), 0, "尚未保存", "返回后保留原来的按钮和顺序。", null)); Button discard = SettingsUi.button(getContext(), "放弃更改", finish); discard.setTag("control-editor-discard"); sheet.content.addView(discard); sheet.content.addView(SettingsUi.button(getContext(), "继续编辑", this::dismissModal));
    }
    private void save() {
        cancelDrag();
        if (!prefs.actions("panel").equals(original)) { DetailSheet sheet = sheet("配置已在别处更新"); sheet.content.addView(SettingsUi.settingRow(getContext(), 0, "本次草稿未保存", "请返回控制中心后重新编辑，以保留新的配置。", null)); sheet.content.addView(SettingsUi.button(getContext(), "返回控制中心", finish)); return; }
        prefs.saveActions("panel", draft); finish.run();
    }
    void showPicker() { if (expanded) return; cancelDrag(); expanded = true; attachCandidates(); }
    private void collapseCandidates() { cancelDrag(); expanded = false; attachCandidates(); }
    private void attachCandidates() {
        if (candidates != null) { candidateState = candidates.snapshot(); getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0); workspace.removeView(candidates); candidates = null; }
        workspace.removeView(expand);
        if (expanded) { candidates = new ControlCandidatesView(getContext(), prefs.panelColumns(), draft, candidateState, this::chooseCandidate, (id, view) -> startDrag(id, false, view), this::collapseCandidates); workspace.addView(candidates); }
        else workspace.addView(expand);
        workspace.requestLayout();
    }
    private void chooseCandidate(String id) {
        if (draft.contains(id)) { reveal(id); return; }
        if (id.startsWith("tile:")) {
            DetailSheet sheet = sheet(ActionCatalog.label(getContext(), id)); sheet.content.addView(SettingsUi.settingRow(getContext(), R.drawable.ic_ms_info, "实验性应用磁贴", "添加不自动注册或授权，实际执行能力由系统与原应用决定。", null));
            option(sheet, "添加到控制中心", R.drawable.ic_ms_add, "control-tile-add", () -> insert(id, draft.size())); option(sheet, "注册到系统快捷设置", R.drawable.ic_ms_settings, "control-tile-register", () -> registerTile.accept(id));
        } else insert(id, draft.size());
    }
    private void checkpoint() { undo = new ArrayList<>(draft); }
    private void insert(String id, int position) {
        int from = draft.indexOf(id);
        if (from < 0 && draft.size() >= 30) { Toast.makeText(getContext(), "最多添加 30 项，请先移除一项", Toast.LENGTH_SHORT).show(); announceForAccessibility("已达 30 项上限"); return; }
        int to = Math.min(Math.max(0, position), from < 0 ? draft.size() : draft.size() - 1); if (from == to) return;
        checkpoint(); if (from >= 0) draft.remove(from); draft.add(to, id); update(); reveal(id); announceForAccessibility(ActionCatalog.label(getContext(), id) + "，第 " + (to + 1) + " 项");
    }
    private void remove(String id) { if (!draft.contains(id)) return; checkpoint(); draft.remove(id); update(); announceForAccessibility("已移除 " + ActionCatalog.label(getContext(), id) + "，可撤销"); }
    private void undo() { if (undo == null) return; cancelDrag(); draft.clear(); draft.addAll(undo); undo = null; update(); announceForAccessibility("已撤销上次操作"); }
    private void update() { selected.items(draft); count.setText(draft.size() + " / 30 项"); title.setContentDescription("控制中心自定义，已选 " + draft.size() + " 项，长按图标拖动"); undoButton.setEnabled(undo != null); if (candidates != null) candidates.selectionChanged(); }
    private void reveal(String id) { selected.post(() -> { View cell = selected.findViewWithTag("control-selected-" + id); if (cell != null && isAttachedToWindow()) cell.requestRectangleOnScreen(new Rect(0, 0, cell.getWidth(), cell.getHeight()), true); }); }
    private void options(String id) {
        int position = draft.indexOf(id); DetailSheet sheet = sheet(ActionCatalog.label(getContext(), id));
        if (position > 0) { option(sheet, "前移一格", R.drawable.ic_ms_keyboard_arrow_up, "order-move-up", () -> insert(id, position - 1)); if (position > 1) option(sheet, "移到开头", R.drawable.ic_ms_keyboard_arrow_up, "order-move-first", () -> insert(id, 0)); }
        if (position + 1 < draft.size()) { option(sheet, "后移一格", R.drawable.ic_ms_keyboard_arrow_down, "order-move-down", () -> insert(id, position + 1)); if (position + 2 < draft.size()) option(sheet, "移到末尾", R.drawable.ic_ms_keyboard_arrow_down, "order-move-last", () -> insert(id, draft.size() - 1)); }
        option(sheet, "移除按钮", R.drawable.ic_ms_close, "order-menu-remove", () -> remove(id));
        if (id.startsWith("tile:")) option(sheet, "注册到系统快捷设置", R.drawable.ic_ms_settings, "control-tile-register", () -> registerTile.accept(id));
    }
    private void option(DetailSheet sheet, String label, int icon, String tag, Runnable action) { View row = SettingsUi.settingRow(getContext(), icon, label, "", () -> { dismissModal(); action.run(); }); row.setTag(tag); sheet.content.addView(row); }
    private DetailSheet sheet(String label) { cancelDrag(); dismissModal(); modal = new DetailSheet(getContext(), label, this::dismissModal); modal.settingsStyle(); addView(modal, new FrameLayout.LayoutParams(-1, -1)); modal.enter(null); return modal; }
    private void dismissModal() { if (modal != null) removeView(modal); modal = null; }

    private boolean startDrag(String id, boolean fromSelected, View origin) {
        if (drag != null || modal != null || !fromSelected && draft.contains(id)) return false;
        if (!fromSelected && draft.size() >= 30) { Toast.makeText(getContext(), "最多添加 30 项，请先移除一项", Toast.LENGTH_SHORT).show(); return false; }
        DragSession session = new DragSession(this, id, fromSelected, origin); drag = session;
        ClipData data = new ClipData("control-editor", new String[]{"application/x-flipcover-shortcut"}, new ClipData.Item(id));
        if (!origin.startDragAndDrop(data, new View.DragShadowBuilder(origin), session, 0)) { drag = null; return false; }
        origin.setAlpha(.35f); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); expand.setText(fromSelected ? "拖到这里移除" : "+ 添加按钮"); return true;
    }
    private boolean dragEvent(DragEvent event) {
        if (!(event.getLocalState() instanceof DragSession session) || session.owner() != this) return false;
        if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) { clearDrag(); return true; }
        if (session != drag) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED: return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                dragX = event.getX(); dragY = event.getY(); locateDrag(); removeCallbacks(edgeScroll); if (scrollStep != 0) postOnAnimation(edgeScroll); return true;
            case DragEvent.ACTION_DRAG_EXITED:
                removeCallbacks(edgeScroll); scrollStep = 0; target = -1; removeTarget = false; selected.clearPreview(); workspace.invalidate(); return true;
            case DragEvent.ACTION_DROP:
                dragX = event.getX(); dragY = event.getY(); locateDrag(); int position = target; boolean remove = removeTarget; String id = drag.id(); clearDrag();
                if (remove) { remove(id); return true; } if (position >= 0) { insert(id, position); return true; } return false;
            default: return true;
        }
    }
    private Rect rect(View view) { int[] origin = new int[2], location = new int[2]; getLocationOnScreen(origin); view.getLocationOnScreen(location); return new Rect(location[0] - origin[0], location[1] - origin[1], location[0] - origin[0] + view.getWidth(), location[1] - origin[1] + view.getHeight()); }
    private void locateDrag() {
        if (drag == null) return; int before = target; Rect selectedArea = rect(selectedScroll); boolean overSelected = selectedArea.contains((int) dragX, (int) dragY);
        removeTarget = drag.selected() && rect(candidates == null ? expand : candidates).contains((int) dragX, (int) dragY); target = -1; scrollStep = 0;
        if (overSelected && (drag.selected() || draft.size() < 30)) {
            Rect gridArea = rect(selected); target = selected.slot(dragX - gridArea.left, dragY - gridArea.top); selected.preview(drag.id(), target);
            int edge = Math.min(Ui.dp(getContext(), 24), selectedScroll.getHeight() / 3); scrollStep = dragY < selectedArea.top + edge ? -Ui.dp(getContext(), 5) : dragY > selectedArea.bottom - edge ? Ui.dp(getContext(), 5) : 0;
            if (target != before) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        } else selected.clearPreview();
        workspace.invalidate();
    }
    void cancelDrag() { if (drag != null) drag.origin().cancelDragAndDrop(); clearDrag(); }
    private void clearDrag() { removeCallbacks(edgeScroll); if (drag != null) drag.origin().setAlpha(1); drag = null; target = -1; removeTarget = false; scrollStep = 0; selected.clearPreview(); expand.setText("+ 添加按钮"); workspace.invalidate(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) cancelDrag(); }
    @Override protected void onDetachedFromWindow() { cancelDrag(); super.onDetachedFromWindow(); }

    private final class Workspace extends ViewGroup {
        private final Paint highlight = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int selectedHeight;
        Workspace(Context c) { super(c); setTag("control-editor-workspace"); highlight.setColor(SettingsUi.DANGER); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec), exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
            if (expanded) {
                int minimum = Ui.dp(getContext(), 48), candidateChrome = candidates.chromeHeight(exactWidth);
                selectedHeight = Math.max(minimum, Math.min(Math.round((height - candidateChrome) * .44f), height - candidateChrome - minimum)); selectedHeight = Math.max(0, Math.min(height, selectedHeight));
                candidates.measure(exactWidth, MeasureSpec.makeMeasureSpec(Math.max(0, height - selectedHeight), MeasureSpec.EXACTLY));
            } else { expand.measure(exactWidth, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)); selectedHeight = Math.max(0, height - expand.getMeasuredHeight()); }
            selectedScroll.measure(exactWidth, MeasureSpec.makeMeasureSpec(selectedHeight, MeasureSpec.EXACTLY)); setMeasuredDimension(width, height);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { selectedScroll.layout(0, 0, getWidth(), selectedHeight); View lower = expanded ? candidates : expand; lower.layout(0, selectedHeight, getWidth(), getHeight()); }
        @Override protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (removeTarget) { highlight.setStyle(Paint.Style.FILL); highlight.setColor(0xCC301817); canvas.drawRoundRect(1, selectedHeight + 1, getWidth() - 1, getHeight() - 1, Ui.dp(getContext(), 20), Ui.dp(getContext(), 20), highlight); highlight.setColor(SettingsUi.DANGER); highlight.setTextSize(Ui.dp(getContext(), 16)); highlight.setTextAlign(Paint.Align.CENTER); canvas.drawText("松手移除 · 可撤销", getWidth() / 2f, selectedHeight + (getHeight() - selectedHeight) / 2f, highlight); }
        }
    }
}
