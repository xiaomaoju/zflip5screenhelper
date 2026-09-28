package io.github.flipcover.controls;

import android.content.Context;
import android.media.AudioManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Panels {
    private final CoverService owner;
    private final Context context;
    private final boolean preview;
    private final Map<String, Tile> buttons = new HashMap<>();
    private LinearLayout body;
    private LevelSlider brightness;
    private Integer confirmedBrightness;
    private NotificationCenterView notificationCenter;
    Panels(CoverService owner) { this(owner, false); }
    Panels(CoverService owner, boolean preview) { this.owner = owner; this.preview = preview; context = owner.screenContext; }
    boolean hasBrightness() { return brightness != null && !preview; }
    void brightnessWorking(boolean value) { if (brightness != null) { brightness.setActivated(value); brightness.setStateDescription(value ? "正在调节外屏亮度" : null); brightness.invalidate(); } }
    View build(String page) {
        body = Ui.column(context);
        switch (page) {
            case "rotation" -> rotation();
            case "notifications" -> notifications();
            case "media" -> media();
            default -> controls();
        }
        return body;
    }
    private final class Tile extends RuntimeVisuals.Cell {
        final ImageView icon;
        final TextView label, detail;
        final FrameLayout face;
        final TextView unknown;
        final boolean compact;
        final String actionName;
        private Boolean actual;
        private boolean working;
        Tile(String id, Runnable action) { this(id, action, false); }
        Tile(String id, Runnable action, boolean compact) {
            super(context); this.compact = compact; actionName = ActionCatalog.label(context, id); setOrientation(VERTICAL); setGravity(Gravity.CENTER);
            setMinimumHeight(Ui.dp(context, compact ? 50 : 54));
            setPadding(Ui.dp(context, compact ? 1 : 5), Ui.dp(context, compact ? 0 : 5), Ui.dp(context, compact ? 1 : 5), Ui.dp(context, compact ? 0 : 10));
            face = new FrameLayout(context); face.setDuplicateParentStateEnabled(true);
            icon = new ImageView(context); icon.setImageDrawable(ActionCatalog.icon(context, id));
            face.addView(icon, new FrameLayout.LayoutParams(Ui.dp(context, 23), Ui.dp(context, 23), Gravity.CENTER));
            unknown = Ui.text(context, "?", 9, Ui.MUTED); unknown.setGravity(Gravity.CENTER); unknown.setVisibility(GONE);
            FrameLayout.LayoutParams marker = new FrameLayout.LayoutParams(Ui.dp(context, 11), Ui.dp(context, 11), Gravity.TOP | Gravity.RIGHT); marker.topMargin = Ui.dp(context, 2); marker.rightMargin = Ui.dp(context, 2); face.addView(unknown, marker);
            addView(face, new LayoutParams(Ui.dp(context, compact ? 40 : 26), Ui.dp(context, compact ? 40 : 26)));
            label = Ui.text(context, actionName, compact ? 9 : 12, Ui.TEXT); if (compact) label.setMaxLines(2); else label.setSingleLine(); label.setEllipsize(TextUtils.TruncateAt.END); label.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            LayoutParams words = new LayoutParams(-1, -2); words.topMargin = Ui.dp(context, compact ? 3 : 8); addView(label, words);
            detail = Ui.text(context, "", 10, Ui.MUTED); detail.setSingleLine(); detail.setGravity(Gravity.CENTER); detail.setPadding(0, Ui.dp(context, 3), 0, 0);
            if (!compact) { detail.setVisibility(GONE); addView(detail); }
            if (compact) face.setBackground(new RuntimeVisuals.Surface(face, 0xE6262A31, Ui.TEXT, Ui.CONTROL_CORNER_DP)); else setBackground(Ui.ripple(context, Ui.SURFACE, 20));
            setFocusable(!preview); setOnClickListener(v -> { if (!preview) action.run(); }); icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); unknown.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setContentDescription(actionName);
        }
        void state(Boolean value) {
            actual = value;
            boolean active = Boolean.TRUE.equals(value); setSelected(active);
            if (!compact) setBackground(Ui.ripple(context, active ? Ui.ACTIVE : Ui.SURFACE, 20));
            icon.setColorFilter(active ? Ui.ON_ACTIVE : Ui.TEXT);
            label.setTextColor(compact || !active ? Ui.TEXT : Ui.ON_ACTIVE); unknown.setText(working ? "…" : "?"); unknown.setVisibility(compact && (working || value == null) ? VISIBLE : GONE);
            detail.setTextColor(active ? Ui.ON_ACTIVE : Ui.MUTED); detail.setText(value == null ? "状态未知" : active ? "已开启" : "已关闭");
            setContentDescription(actionName + "，" + (working ? "正在执行" : detail.getText())); setStateDescription(working ? "正在执行" : detail.getText());
        }
        void working(boolean value) { working = value; setActivated(value); setEnabled(!value); state(actual); }
    }
    boolean working(String id) { Tile tile = buttons.get(id); return tile != null && tile.working; }
    void working(String id, boolean value) { Tile tile = buttons.get(id); if (tile != null) tile.working(value); }
    private void controls() {
        List<String> actions = owner.prefs.actions("panel");
        ControlDashboard dashboard = new ControlDashboard(); dashboard.setTag("control-dashboard");
        for (String id : actions) {
            Tile tile = new Tile(id, () -> { if (id.equals("rotation")) owner.toggleRotation(); else owner.act(id); }, true);
            tile.label.setTextSize(new int[]{9, 10, 12}[owner.prefs.panelLabelSize()]); tile.label.setMaxLines(roomyLabels() || id.equals("system_controls") ? 2 : 1); tile.label.setVisibility(owner.prefs.panelLabels() ? View.VISIBLE : View.GONE);
            tile.setTag("control-" + id); tile.setOnLongClickListener(v -> { if (!preview) owner.showDetails(id, tile); return true; }); buttons.put(id, tile); dashboard.addTile(tile);
        }
        if (actions.isEmpty()) Ui.add(body, Ui.button(context, preview ? "暂无控制按钮 · 示意" : "添加控制按钮", () -> { if (!preview) owner.editControls(); }));
        if (owner.prefs.panelBrightness() || owner.prefs.panelVolume()) {
            LinearLayout sliders = Ui.row(context); sliders.setGravity(Gravity.TOP); sliders.setTag("control-sliders");
            if (owner.prefs.panelBrightness()) {
                brightness = new LevelSlider(context, "外屏亮度", R.drawable.ic_ms_brightness_6, 5, 100, 5, value -> { if (!preview) owner.setBrightness(Panels.this, value); }); brightness.setEnabled(false);
                brightness.setTag("control-brightness"); brightness.setOnLongClickListener(v -> { if (!preview) owner.showDetails("brightness", brightness); return true; });
                sliders.addView(brightness, new LinearLayout.LayoutParams(Ui.dp(context, 44), -1));
            }
            if (owner.prefs.panelVolume()) {
                AudioManager audio = preview ? null : context.getSystemService(AudioManager.class);
                LevelSlider volume = new LevelSlider(context, "媒体音量", R.drawable.ic_ms_volume_up, 0, preview ? 100 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), preview ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC), value -> { if (!preview) audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0); });
                volume.setEnabled(!preview); volume.setTag("control-volume"); volume.setOnLongClickListener(v -> { if (!preview) owner.showDetails("volume", volume); return true; });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(context, 44), -1); params.leftMargin = sliders.getChildCount() == 0 ? 0 : Ui.dp(context, 5); sliders.addView(volume, params);
            }
            dashboard.sliders = sliders; dashboard.addView(sliders);
        }
        if (owner.prefs.panelMedia()) { dashboard.media = new MediaCardView(owner, true, preview); dashboard.addView(dashboard.media); }
        dashboard.addView(dashboard.positionHint);
        if (!actions.isEmpty() || dashboard.sliders != null || dashboard.media != null) body.addView(dashboard, new LinearLayout.LayoutParams(-1, 0, 1)); updateStates();
    }
    private boolean roomyLabels() { return context.getResources().getConfiguration().fontScale > 1.3f || owner.prefs.panelLabelSize() == 2 || owner.prefs.panelDensity() == 2; }
    /** Compact labels keep side tools; large type and genuinely narrow widths can stack. */
    private final class ControlDashboard extends android.view.ViewGroup {
        private final java.util.ArrayList<Tile> tiles = new java.util.ArrayList<>();
        LinearLayout sliders; MediaCardView media;
        int columns, rows, gridWidth, gridHeight, sliderWidth, sliderHeight, toolWidth, toolHeight, gap, hintHeight;
        boolean stacked;
        final TextView positionHint;
        ControlDashboard() { super(context); positionHint = Ui.text(context, "空间不足，工具区已移到下方", 10, Ui.MUTED); positionHint.setTag("control-tools-hint"); positionHint.setVisibility(GONE); }
        void addTile(Tile tile) { tiles.add(tile); addView(tile); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), requested = owner.prefs.panelColumns(), density = owner.prefs.panelDensity(); gap = Ui.dp(context, switch (density) { case 0 -> 4; case 2 -> 8; default -> 6; });
            boolean tools = sliders != null || media != null;
            sliderWidth = sliders == null ? 0 : Ui.dp(context, 44) * sliders.getChildCount() + Ui.dp(context, 5) * (sliders.getChildCount() - 1);
            int sidePadding = tools ? Ui.dp(context, 8) : 0;
            toolWidth = tools ? Math.max(sliderWidth, media == null ? 0 : Ui.dp(context, 93)) + sidePadding : 0;
            int sideWidth = width - toolWidth;
            int minimumCell = Ui.dp(context, roomyLabels() ? 48 : 28);
            if (roomyLabels() && owner.prefs.panelLabels()) for (Tile tile : tiles) minimumCell = Math.max(minimumCell, (int) Math.ceil(tile.label.getPaint().measureText(tile.label.getText().toString())) + Ui.dp(context, 8));
            String position = owner.prefs.panelToolsPosition();
            stacked = tools && (tiles.isEmpty() || position.equals("bottom") || sideWidth / requested < minimumCell); gridWidth = stacked ? width : sideWidth;
            boolean fallback = tools && !tiles.isEmpty() && stacked && position.equals("side"); positionHint.setVisibility(fallback ? VISIBLE : GONE); hintHeight = 0;
            if (fallback) { positionHint.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)); hintHeight = positionHint.getMeasuredHeight() + gap; }
            int maxColumns = requested, cellWidth = gridWidth / requested, labelHeight = 0;
            if (owner.prefs.panelLabels()) for (Tile tile : tiles) {
                tile.label.measure(MeasureSpec.makeMeasureSpec(Math.max(1, cellWidth - tile.getPaddingLeft() - tile.getPaddingRight()), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                labelHeight = Math.max(labelHeight, tile.label.getMeasuredHeight());
            }
            int minimumRow = Math.max(Ui.dp(context, 48), labelHeight + Ui.dp(context, switch (density) { case 0 -> 39; case 2 -> 51; default -> 43; }));
            int mediaWidth = media == null ? 0 : stacked ? width - sliderWidth - (sliders == null ? 0 : gap) : toolWidth - sidePadding;
            int mediaMinimum = media == null ? 0 : media.minimumCardHeight(mediaWidth), sliderMinimum = sliders == null ? 0 : Ui.dp(context, stacked ? 92 : 68);
            int betweenTools = sliders != null && media != null ? gap : 0, rowCount = (tiles.size() + maxColumns - 1) / maxColumns;
            toolHeight = stacked ? Math.max(sliderMinimum, mediaMinimum) : sliderMinimum + betweenTools + mediaMinimum;
            int gridGap = tools && rowCount > 0 && stacked ? gap : 0;
            int natural = (stacked ? rowCount * minimumRow + gridGap + toolHeight : Math.max(toolHeight, rowCount * minimumRow)) + hintHeight;
            int height = resolveSize(natural, heightSpec); columns = maxColumns;
            rows = Math.max(1, rowCount); gridHeight = rowCount == 0 ? 0 : stacked ? Math.max(1, height - hintHeight - gridGap - toolHeight) : height - hintHeight;
            // A sparse row without tools stays near the header instead of floating mid-screen.
            if (!tools && rowCount == 1) gridHeight = Math.min(gridHeight, minimumRow);
            for (Tile tile : tiles) {
                int cellHeight = gridHeight / rows; tile.label.getLayoutParams().height = labelHeight;
                int textHeight = labelHeight == 0 ? 0 : labelHeight + Ui.dp(context, 3);
                int inset = cellWidth >= Ui.dp(context, 44) ? Ui.dp(context, switch (density) { case 0 -> 4; case 2 -> 12; default -> 8; }) : Ui.dp(context, 4);
                int face = Math.max(1, Math.min(Ui.dp(context, switch (density) { case 0 -> 48; case 2 -> 64; default -> 56; }), Math.min(cellWidth - inset, cellHeight - textHeight)));
                FrameLayout.LayoutParams marker = (FrameLayout.LayoutParams) tile.unknown.getLayoutParams();
                marker.width = marker.height = Ui.dp(context, 12); marker.topMargin = marker.rightMargin = Ui.dp(context, 2);
                // This is an icon-like state mark; text labels still retain system font scaling.
                tile.unknown.setIncludeFontPadding(false); tile.unknown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(context, 9));
                int symbol = Math.max(1, Math.min(Ui.dp(context, 23), face - marker.width - Ui.dp(context, 4)));
                FrameLayout.LayoutParams glyph = (FrameLayout.LayoutParams) tile.icon.getLayoutParams(); glyph.width = glyph.height = symbol; glyph.gravity = Gravity.CENTER;
                glyph.leftMargin = glyph.topMargin = 0;
                LinearLayout.LayoutParams icon = (LinearLayout.LayoutParams) tile.face.getLayoutParams(); icon.width = icon.height = face;
                tile.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY));
            }
            sliderHeight = sliders == null ? 0 : stacked ? toolHeight : Math.max(sliderMinimum, Math.min(Ui.dp(context, 136), Math.min(Math.round(height * .5f), height - hintHeight - betweenTools - mediaMinimum)));
            if (sliders != null) sliders.measure(MeasureSpec.makeMeasureSpec(sliderWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sliderHeight, MeasureSpec.EXACTLY));
            int mediaHeight = stacked ? toolHeight : height - hintHeight - sliderHeight - betweenTools;
            if (media != null) media.measure(MeasureSpec.makeMeasureSpec(Math.max(1, mediaWidth), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(1, media.condensed() ? mediaMinimum : mediaHeight), MeasureSpec.EXACTLY)); setMeasuredDimension(width, height);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int left = !stacked && owner.prefs.leftHand() ? toolWidth : 0;
            for (int i = 0; i < tiles.size(); i++) { int x = left + i % columns * gridWidth / columns, y = i / columns * gridHeight / rows; Tile tile = tiles.get(i); tile.layout(x, y, x + tile.getMeasuredWidth(), y + tile.getMeasuredHeight()); }
            if (stacked) {
                int top = gridHeight + (tiles.isEmpty() ? 0 : gap), sliderX = owner.prefs.leftHand() ? 0 : getWidth() - sliderWidth, mediaX = owner.prefs.leftHand() && sliders != null ? sliderWidth + gap : 0;
                if (sliders != null) sliders.layout(sliderX, top, sliderX + sliderWidth, top + sliderHeight);
                if (media != null) media.layout(mediaX, top, mediaX + media.getMeasuredWidth(), top + media.getMeasuredHeight());
            } else {
                int x = owner.prefs.leftHand() ? 0 : gridWidth + Ui.dp(context, 8);
                if (sliders != null) sliders.layout(x, 0, x + sliderWidth, sliderHeight);
                int top = sliderHeight + (sliders != null && media != null ? gap : 0);
                if (media != null) media.layout(x, top, x + media.getMeasuredWidth(), top + media.getMeasuredHeight());
            }
            if (positionHint.getVisibility() == VISIBLE) positionHint.layout(0, getHeight() - positionHint.getMeasuredHeight(), getWidth(), getHeight());
        }
    }
    void updateStates() {
        for (Map.Entry<String, Tile> entry : buttons.entrySet()) {
            String id = entry.getKey();
            if (preview) { entry.getValue().state(null); continue; }
            if (List.of("wifi", "bluetooth", "data", "torch", "dnd", "airplane", "system_controls").contains(id)) entry.getValue().state(owner.on(id));
            if (id.equals("system_controls")) { Boolean state = owner.on(id); entry.getValue().icon.setImageDrawable(Ui.icon(context, Boolean.FALSE.equals(state) ? R.drawable.ic_ms_toggle_off : R.drawable.ic_ms_toggle_on, Boolean.TRUE.equals(state) ? Ui.ON_ACTIVE : Ui.TEXT)); }
            if (id.equals("rotation")) { entry.getValue().state(owner.rotationAutomatic()); entry.getValue().label.setText(owner.rotationAutomatic() ? "自动旋转" : "旋转锁定"); }
        }
    }
    void brightness(ShizukuBridge.Result result) {
        if (brightness == null) return;
        if (result.retryable) return;
        try {
            if (!result.ok) throw new IllegalStateException();
            float actual = Float.parseFloat(result.output);
            if (!Float.isFinite(actual) || actual < 0 || actual > 1) throw new IllegalStateException();
            confirmedBrightness = Math.round(actual * 100);
            brightness.setEnabled(true); brightness.setValue(confirmedBrightness);
        } catch (RuntimeException e) { confirmedBrightness = null; brightness.setEnabled(false); }
    }
    void brightnessWritten(ShizukuBridge.Result result) {
        if (result.ok) brightness(result);
        else if (brightness != null) {
            // A limit can reject the requested value while still returning a valid current value.
            try {
                float actual = Float.parseFloat(result.output);
                if (Float.isFinite(actual) && actual >= 0 && actual <= 1) { brightness(new ShizukuBridge.Result(true, result.message, result.output)); return; }
            } catch (RuntimeException ignored) { }
            // A rejected or busy write does not make a previously readable display unsupported.
            if (confirmedBrightness != null) { brightness.setEnabled(true); brightness.setValue(confirmedBrightness); }
        }
    }
    void chooseState(String id) {
        body.removeAllViews(); buttons.clear(); brightness = null;
        Ui.add(body, Ui.heading(context, ActionCatalog.label(context, id), 20));
        Ui.add(body, Ui.text(context, "当前状态未知，请选择操作。此开关影响整个手机。", 12, Ui.MUTED));
        LinearLayout options = Ui.row(context);
        options.addView(Ui.iconButton(context, R.drawable.ic_ms_toggle_on, "开启" + ActionCatalog.label(context, id), () -> owner.shell(id, 1, "", result -> { if (result.ok) owner.showPanel("controls"); })));
        options.addView(Ui.iconButton(context, R.drawable.ic_ms_toggle_off, "关闭" + ActionCatalog.label(context, id), () -> owner.shell(id, 0, "", result -> { if (result.ok) owner.showPanel("controls"); })));
        Ui.add(body, options);
    }
    private void rotation() {
        String[] names = {"正向", "向右", "倒置", "向左"};
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = Ui.row(context);
            for (int i = rowIndex * 2; i < rowIndex * 2 + 2; i++) {
                int target = i; boolean active = owner.display.getRotation() == i;
                Tile tile = new Tile("rotation", () -> owner.lockRotation(target));
                tile.setMinimumHeight(Ui.dp(context, 52)); tile.setPadding(Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 6));
                tile.face.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(context, 22), Ui.dp(context, 22)));
                tile.icon.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(context, 20), Ui.dp(context, 20), Gravity.CENTER));
                ((LinearLayout.LayoutParams) tile.label.getLayoutParams()).topMargin = Ui.dp(context, 3);
                tile.detail.setTextSize(9); tile.detail.setMinHeight(Ui.dp(context, 12)); tile.detail.setPadding(0, Ui.dp(context, 1), 0, 0);
                tile.icon.setImageDrawable(Ui.icon(context, R.drawable.ic_ms_phone_android, active ? Ui.ON_ACTIVE : Ui.TEXT)); tile.icon.setRotation(i * 90);
                tile.label.setText(names[i] + " · " + i * 90 + "°"); tile.label.setTextSize(12); tile.label.setTextColor(active ? Ui.ON_ACTIVE : Ui.TEXT);
                tile.detail.setText(active ? "当前方向" : names[i]); tile.detail.setTextColor(active ? Ui.ON_ACTIVE : Ui.MUTED);
                tile.setSelected(active); tile.setBackground(Ui.ripple(context, active ? Ui.ACTIVE : Ui.SURFACE, 20));
                tile.setContentDescription(names[i] + "，" + i * 90 + "度" + (active ? "，当前方向" : "")); Ui.weighted(row, tile);
            }
            Ui.add(body, row);
        }
        Ui.add(body, Ui.button(context, "交回系统旋转控制", () -> { owner.stopAutomaticRotation(); owner.shell("rotation_auto", 0, "", null); }));
        Ui.add(body, Ui.text(context, "以系统方向为准。请关闭其他工具的自动旋转，避免互相覆盖。", 11, Ui.MUTED));
    }
    private void notifications() {
        notificationCenter = new NotificationCenterView(context, new NotificationCenterView.Actions() {
            @Override public void open(StatusBarNotification item) { owner.openNotification(item); }
            @Override public void settings(StatusBarNotification item) { owner.openNotificationSettings(item); }
            @Override public void permission() { owner.openSettings("permissions"); }
            @Override public void clear(List<StatusBarNotification> items) {
                int requested = CoverNotifications.dismiss(items);
                owner.message(requested < 0 ? "通知连接不可用，未能清除" : requested == 0 ? "通知已变化，未清除新的内容" : "已请求清除 " + requested + " 条通知");
            }
        });
        body.addView(notificationCenter, new LinearLayout.LayoutParams(-1, -2)); refreshNotifications();
    }
    View notificationNotice() { return notificationCenter == null ? null : notificationCenter.newNotice; }
    View notificationClearAction() { return notificationCenter == null ? null : notificationCenter.clearAll; }
    void bindNotificationTitle(TextView title) { if (notificationCenter != null) notificationCenter.bindPanelTitle(title); }
    void closeNotificationActions() { if (notificationCenter != null) notificationCenter.closeActions(); }
    void refreshNotifications() { updateNotifications(CoverNotifications.ready(), CoverNotifications.snapshot()); }
    void updateNotifications(boolean ready, List<StatusBarNotification> snapshot) { if (notificationCenter != null) notificationCenter.update(ready, snapshot); }
    private void media() {
        Ui.add(body, Ui.text(context, "控制当前正在使用的播放器", 12, Ui.MUTED));
        LinearLayout row = Ui.row(context);
        int[] icons = {R.drawable.ic_ms_skip_previous, R.drawable.ic_ms_play_pause, R.drawable.ic_ms_skip_next};
        int[] keys = {KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT};
        String[] labels = {"上一首", "播放 / 暂停", "下一首"};
        for (int i = 0; i < keys.length; i++) {
            int key = keys[i]; Tile tile = new Tile("media", () -> owner.media(key)); tile.icon.setImageDrawable(Ui.icon(context, icons[i], Ui.TEXT));
            tile.label.setText(labels[i]); tile.label.setTextSize(11); tile.setContentDescription(labels[i]); Ui.weighted(row, tile);
        }
        Ui.add(body, row); Ui.add(body, Ui.text(context, "播放状态由播放器显示", 11, Ui.MUTED));
    }
}
