package io.github.flipcover.controls;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/** The full settings page and glass quick sheet edit the same local device records. */
final class InputDevicePanel extends LinearLayout {
    private final InputDevices inputs;
    private final boolean quick;
    private final PanelGlassSession glass;
    private final Runnable observer = this::refresh;
    private String selected = "", signature = "";
    private TextView status;
    private boolean observing, choosing;
    InputDevicePanel(Context context, boolean quick, PanelGlassSession glass) { super(context); this.quick = quick; this.glass = glass; inputs = CoverApp.inputs(context); setOrientation(VERTICAL); setTag("input-device-panel"); refresh(); }
    private void refresh() {
        List<InputDevices.Device> devices = inputs.connected(); String next = devices.toString() + inputs.saved() + inputs.enabled() + inputs.suspended + selected + choosing;
        if (next.equals(signature)) { if (status != null) status.setText(inputs.status); return; } signature = next;
        removeAllViews();
        LinearLayout master = group();
        if (quick) { LinearLayout row = PanelUi.row(getContext(), R.drawable.ic_input_devices, "设备操作规则", "仅在助手界面内生效", () -> inputs.enabled(!inputs.enabled())); row.addView(new GlassToggle(getContext(), "设备操作规则", inputs.enabled(), () -> inputs.enabled(!inputs.enabled()))); master.addView(row); }
        else SettingsUi.toggle(master, "设备操作规则", "按设备使用方向导航或指针操作", inputs.enabled(), inputs::enabled);
        status = label(inputs.status, true); addView(status);
        if (!quick) addView(label("方向键选择，Enter确认，Esc返回。长按Esc半秒或F6直达快捷栏；选中“⋯”打开更多。输入框内方向键移动光标。", true));
        InputDevices.Device current = null;
        for (InputDevices.Device d : devices) if (d.key().equals(selected)) current = d;
        if (current == null && selected.isEmpty()) { current = inputs.device(inputs.lastDevice); if (current == null && !devices.isEmpty()) current = devices.get(0); if (current != null) selected = current.key(); }
        if (current == null && !selected.isEmpty()) { org.json.JSONObject record = inputs.saved().optJSONObject(selected); if (record != null) current = new InputDevices.Device(-1, selected, record.optString("name", "已保存设备"), "未连接", false); }
        if (current == null) { group().addView(label("未检测到鼠标或触控板。请先通过系统蓝牙设置配对，或连接 USB 输入设备。", false)); }
        else {
            InputDevices.Device device = current; LinearLayout settings = group();
            settings.addView(deviceRow("profile:" + device.key(), device.name(), device.type() + (device.ambiguous() ? " · 标识重复，暂用指针" : device.id() < 0 ? " · 配置保留" : " · 已连接"), () -> { choosing = !choosing; signature = ""; refresh(); }));
            if (quick && glass != null && glass.active()) {
                OriginalLiquidTabs tabs = new OriginalLiquidTabs(getContext(), index -> inputs.save(device, index == 0 ? "pointer" : "direction", inputs.sensitivity(device)), new String[]{"指针操作", "方向导航"}, new int[]{}, PanelUi.STRIP);
                tabs.setTag("input-mode"); tabs.source("direction".equals(inputs.mode(device)) ? 1 : 0); tabs.glass(glass); tabs.setEnabled(!device.ambiguous()); settings.addView(tabs, new LayoutParams(-1, Ui.dp(getContext(), PanelUi.STRIP)));
            } else {
                settings.addView(choice("指针操作", "pointer".equals(inputs.mode(device)), () -> inputs.save(device, "pointer", inputs.sensitivity(device))));
                settings.addView(choice("方向导航", "direction".equals(inputs.mode(device)), () -> inputs.save(device, "direction", inputs.sensitivity(device))));
            }
            settings.addView(label("灵敏度 · 普通系统指针速度仍由系统管理", true));
            LinearLayout speeds = new LinearLayout(getContext()); String[] names = {"慢", "标准", "快"};
            for (int i = 0; i < names.length; i++) { int value = i; View button = choice(names[i], inputs.sensitivity(device) == i, () -> inputs.save(device, inputs.mode(device), value)); speeds.addView(button, new LayoutParams(0, -2, 1)); } settings.addView(speeds);
        }
        if (!quick || choosing) { LinearLayout list = group(); list.addView(label("已连接设备", true)); for (InputDevices.Device d : devices) list.addView(deviceRow("connected:" + d.key(), d.name(), d.type() + " · " + (inputs.mode(d).equals("direction") ? "方向导航" : "指针操作"), () -> { selected = d.key(); choosing = false; refresh(); })); }
        LinearLayout actions = group();
        actions.addView(row("识别设备", "随后移动设备，确认是哪一台", () -> inputs.identify(id -> { InputDevices.Device d = inputs.device(id); if (d != null) { selected = d.key(); inputs.status("已识别：" + d.name()); refresh(); } })));
        actions.addView(row(inputs.suspended ? "恢复设备规则" : "临时恢复指针", "不清除已保存配置", () -> inputs.suspend(!inputs.suspended)));
        if (!quick) {
            actions.addView(row("输入测试", "选中下面的格子验证移动和点击", () -> test()));
            org.json.JSONObject records = inputs.saved(); java.util.Iterator<String> keys = records.keys(); LinearLayout saved = group(); saved.addView(label("已保存设备 · 仅保存在本机", true));
            while (keys.hasNext()) { String key = keys.next(); org.json.JSONObject record = records.optJSONObject(key); if (record == null) continue; saved.addView(deviceRow("saved:" + key, record.optString("name", "设备"), "查看或修改配置", () -> { selected = key; refresh(); })); saved.addView(deviceRow("forget:" + key, "移除此设备配置", "恢复默认指针操作", () -> { inputs.forget(key); if (selected.equals(key)) selected = ""; refresh(); })); }
        }
    }
    private void test() {
        View old = findViewWithTag("input-test"); if (old != null) removeView(old);
        LinearLayout area = group(); area.setTag("input-test"); area.addView(label("测试区：仅更新计数，不启动应用", false));
        for (int row = 0; row < 2; row++) { LinearLayout line = new LinearLayout(getContext()); for (int col = 0; col < 3; col++) { android.widget.Button button = Ui.button(getContext(), String.valueOf(row * 3 + col + 1), null); final int[] count = {0}; button.setOnClickListener(v -> button.setText("✓ " + ++count[0])); line.addView(button, new LayoutParams(0, -2, 1)); } area.addView(line); }
    }
    private LinearLayout group() { LinearLayout result = quick ? Ui.column(getContext()) : SettingsUi.group(getContext()); LayoutParams lp = new LayoutParams(-1, -2); lp.bottomMargin = Ui.dp(getContext(), quick ? 4 : 16); addView(result, lp); return result; }
    private TextView label(String text, boolean secondary) { TextView label = quick ? Ui.text(getContext(), text, secondary ? PanelUi.SECONDARY : PanelUi.BODY, secondary ? Ui.MUTED : Ui.TEXT) : SettingsUi.text(getContext(), text, 14, secondary ? SettingsUi.MUTED : SettingsUi.TEXT); label.setPadding(Ui.dp(getContext(), 8), Ui.dp(getContext(), 6), Ui.dp(getContext(), 8), Ui.dp(getContext(), 6)); return label; }
    private View row(String title, String description, Runnable action) { return quick ? PanelUi.row(getContext(), 0, title, description, action) : SettingsUi.settingRow(getContext(), 0, title, description, action); }
    private View deviceRow(String key, String title, String description, Runnable action) { View row = row(title, description, action); InputNavigation.bind(row, "device:" + key, InputNavigation.Region.LIST, row::performClick, null); return row; }
    private View choice(String text, boolean checked, Runnable action) { View row = quick ? PanelUi.button(getContext(), (checked ? "✓ " : "") + text, action) : SettingsUi.settingRow(getContext(), 0, (checked ? "✓ " : "") + text, "", action); row.setTag("input-choice:" + selected + ":" + text); row.setSelected(checked); row.setContentDescription(text + (checked ? "，已选中" : "")); return row; }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (!observing) { observing = true; inputs.observe(observer); } refresh(); }
    @Override protected void onDetachedFromWindow() { if (observing) { observing = false; inputs.unobserve(observer); inputs.cancelIdentify(); } super.onDetachedFromWindow(); }
}
