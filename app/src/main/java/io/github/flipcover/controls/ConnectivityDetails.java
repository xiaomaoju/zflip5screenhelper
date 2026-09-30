package io.github.flipcover.controls;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Map;

/** NFC / hotspot content only; uses ControlDetails rows and the existing DetailSheet geometry. */
final class ConnectivityDetails implements AutoCloseable {
    private final CoverService owner;
    private final DetailSheet sheet;
    private final ControlDetails rows;
    private final String id;
    private final Context context;
    private final ShizukuBridge bridge;
    private final int display;
    private JSONObject snapshot, editBase, pending;
    private String page = "main", returnPage = "main";
    private boolean closed, busy, reading, revealed, readPending;
    private String operationNotice = "";
    private int readGeneration;
    private EditText name, password;
    private Bitmap qr;
    private ImageView qrView;
    private TextView feedback;
    private View back, refresh;
    private LinearLayout nfcPower, nfcSecureRow;
    private TextView nfcStatus, nfcPaymentStatus, nfcSecureStatus;
    private GlassToggle nfcToggle, nfcSecureToggle;
    private boolean nfcTransition;
    private final Runnable readLater = () -> read(4);
    private final Runnable changed = this::scheduleRead;
    private void scheduleRead() { if (!closed) { if (!bridge.connected()) { read(0); return; } readPending = true; owner.main.removeCallbacks(readLater); owner.main.postDelayed(readLater, 180); } }

    ConnectivityDetails(CoverService owner, DetailSheet sheet, ControlDetails rows, String id) {
        this.owner = owner; this.sheet = sheet; this.rows = rows; this.id = id; context = owner.screenContext; bridge = CoverApp.bridge(context); display = owner.display == null ? -1 : owner.display.getDisplayId();
    }
    void open() {
        sheet.stableViewport(id.equals("nfc") ? 256 : 312);
        back = Ui.iconButton(context, R.drawable.ic_ms_arrow_back, "返回详情", this::back); sheet.extraHeader(back);
        refresh = Ui.iconButton(context, R.drawable.ic_ms_refresh, "刷新连接状态", () -> read(4)); sheet.extraHeader(refresh);
        sheet.onBack(this::back); sheet.onCloseRequest(this::canClose);
        bridge.addObserver(changed); bridge.addConnectivityObserver(changed, id.equals("hotspot")); main(); read(4);
    }
    private boolean live() { return !closed && owner.connectivityDisplay(display); }
    private void read(int retries) {
        if (closed) return;
        if (!bridge.connected()) {
            readGeneration++; busy = false; reading = false; readPending = false; back.setEnabled(true); refresh.setEnabled(true); snapshot = null; clearDraft(); page = "main"; revealed = false; main(); say(bridge.status() + "；可使用下方系统入口"); return;
        }
        if (busy || reading) { readPending = true; return; }
        if (!live()) return;
        readPending = false; reading = true; int generation = ++readGeneration;
        bridge.run(id + "_details", display, 0, "", result -> {
            if (closed || generation != readGeneration) return;
            reading = false; if (!live()) return;
            if (result.retryable && retries > 0) { owner.main.postDelayed(() -> { if (!closed && generation == readGeneration) read(retries - 1); }, 180); return; }
            if (!result.ok) { if (result.retryable) { if (nfcTransition && nfcPower!=null && snapshot!=null) { snapshot.remove("state"); updateNfc(); owner.connectivityStateChanged(id,-1); } say(result.message); return; } snapshot = null; clearDraft(); revealed = false; main(); say(result.message); return; }
            try { snapshot = new JSONObject(result.output); owner.connectivityStateChanged(id, snapshot.optInt("state", -1)); if (page.equals("main")) main(); else if (page.equals("clients")) clients(); else if (page.equals("qr")) share(); }
            catch (Exception error) { snapshot = null; clearDraft(); revealed = false; main(); say("读取结果不可用，请刷新或使用系统设置"); }
            if (!operationNotice.isEmpty()) { say(operationNotice); operationNotice = ""; }
            if (readPending) { owner.main.removeCallbacks(readLater); owner.main.postDelayed(readLater, 180); }
        });
    }
    private void clear(String title, String next) {
        nfcTransition=false; nfcPower=null; nfcStatus=null; nfcPaymentStatus=null; nfcSecureStatus=null; nfcToggle=null; nfcSecureRow=null; nfcSecureToggle=null;
        releaseQr(); page = next; sheet.content.removeAllViews(); sheet.title.setText(title);
        back.setVisibility(next.equals("main") ? View.GONE : View.VISIBLE);
        refresh.setVisibility(next.equals("main") || next.equals("clients") ? View.VISIBLE : View.GONE);
        feedback = Ui.text(context, "", 11, Ui.MUTED); feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sheet.content.addView(feedback, new LinearLayout.LayoutParams(-1, -2)); feedback.setVisibility(View.GONE);
        sheet.footer(next.equals("main") ? id.equals("nfc") ? "更多 NFC 设置" : "更多热点设置" : "返回", next.equals("main") ? this::systemSettings : this::back);
    }
    private void say(String message) { if (!closed && nfcStatus!=null && page.equals("main")) nfcStatus.setText(message); else if (!closed && feedback != null) { feedback.setText(message); feedback.setVisibility(View.VISIBLE); } }
    private void main() {
        if (id.equals("nfc") && page.equals("main") && nfcPower!=null && snapshot!=null && snapshot.optBoolean("supported") && (nfcSecureRow!=null)==snapshot.optBoolean("secureSupported")) { updateNfc(); return; }
        clear(id.equals("nfc") ? "NFC" : "移动热点", "main");
        if (snapshot == null) {
            rows.note(bridge.connected() ? "正在读取系统状态…" : bridge.status() + " · 状态未知");
            LinearLayout unknown=(LinearLayout)rows.row(id.equals("nfc") ? "NFC" : "移动热点","状态未知 · 整机开关",id.equals("nfc") ? R.drawable.ic_nfc : R.drawable.ic_hotspot,this::chooseSwitch); unknown.addView(new GlassToggle(context,id.equals("nfc") ? "NFC" : "移动热点",null,unknown::performClick));
            if (!bridge.connected()) rows.row("查看授权状态", "由你在系统中授权", R.drawable.ic_ms_settings, () -> owner.openSettings("permissions"));
            return;
        }
        if (id.equals("nfc") && !snapshot.optBoolean("supported")) { rows.note("这台设备未报告 NFC 硬件"); return; }
        int state = snapshot.optInt("state", -1);
        LinearLayout power = (LinearLayout) rows.row(id.equals("nfc") ? "NFC" : "移动热点", (state < 0 ? "状态未知或正在切换" : state == 1 ? "已开启" : "已关闭") + " · 整机开关", id.equals("nfc") ? R.drawable.ic_nfc : R.drawable.ic_hotspot, () -> { int current=snapshot==null ? -1 : snapshot.optInt("state",-1); if (current < 0) chooseSwitch(); else run(id, current == 1 ? 0 : 1, ""); });
        power.setTag("connectivity-power"); power.setStateDescription(state < 0 ? "未知" : state == 1 ? "已开启" : "已关闭");
        GlassToggle toggle=new GlassToggle(context,id.equals("nfc") ? "NFC" : "移动热点",state<0 ? null : state==1,power::performClick); toggle.setTag("connectivity-toggle"); power.addView(toggle);
        if (id.equals("nfc")) {
            nfcPower=power; nfcToggle=toggle; nfcStatus=subtitle(power); nfcStatus.setMinLines(2); nfcStatus.setMaxLines(2); nfcStatus.setEllipsize(android.text.TextUtils.TruncateAt.END); nfcStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            nfcPaymentStatus=subtitle((LinearLayout)rows.row("默认付款应用", snapshot.optString("payment", "暂不可用") + " · 更换需系统确认", R.drawable.ic_ms_apps, () -> owner.launch(new Intent(Settings.ACTION_NFC_PAYMENT_SETTINGS)))); nfcPaymentStatus.setMinLines(2); nfcPaymentStatus.setMaxLines(2); nfcPaymentStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (snapshot.optBoolean("secureSupported")) {
                int secure = snapshot.optInt("secure", -1);
                LinearLayout row = (LinearLayout)rows.row("仅解锁时使用", secure < 0 ? "状态未知，请使用系统设置" : secure == 1 ? "已开启" : "已关闭", R.drawable.ic_ms_lock, () -> run("nfc_secure", snapshot.optInt("secure",-1)==1 ? 0 : 1, "")); row.setEnabled(state == 1 && secure >= 0); GlassToggle secureToggle=new GlassToggle(context,"仅解锁时使用",secure<0 ? null : secure==1,row::performClick); secureToggle.setEnabled(row.isEnabled()); row.addView(secureToggle); nfcSecureRow=row; nfcSecureToggle=secureToggle; nfcSecureStatus=subtitle(row); nfcSecureStatus.setMinLines(2); nfcSecureStatus.setMaxLines(2);
            }
            rows.note("付款仍需遵循钱包与系统的验证要求。");
            return;
        }
        if (!snapshot.optBoolean("configAvailable")) { rows.note("热点配置暂不可用，请使用系统设置。开关仍可单独尝试。"); return; }
        String secret = snapshot.optString("password");
        LinearLayout config = (LinearLayout) rows.row(snapshot.optString("name", "热点名称"), (secret.isEmpty() ? "无密码" : revealed ? secret : "••••••••") + " · " + HotspotConfiguration.securityName(snapshot.optInt("security", -1)), R.drawable.ic_ms_edit, this::edit);
        config.setTag("hotspot-config"); View eye = Ui.iconButton(context, revealed ? R.drawable.ic_ms_lock : R.drawable.ic_connectivity_visibility, revealed ? "隐藏热点密码" : "显示热点密码", () -> { revealed = !revealed; main(); }); config.addView(eye);
        LinearLayout quick = Ui.row(context); Button share = PanelUi.button(context, "扫码连接", this::share), clients = PanelUi.button(context, "连接设备 " + (snapshot.optBoolean("clientsAvailable") ? snapshot.optJSONArray("clients").length() : "—"), this::clients);
        share.setTag("hotspot-share"); clients.setTag("hotspot-clients"); LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1); half.setMargins(0, Ui.dp(context, 5), Ui.dp(context, 3), Ui.dp(context, 5)); quick.addView(share, half); quick.addView(clients, new LinearLayout.LayoutParams(0, -2, 1)); sheet.content.addView(quick);
        rows.row("频段", HotspotConfiguration.bandName(snapshot.optInt("band", -1)), R.drawable.ic_ms_wifi, this::bands).setTag("hotspot-band");
        rows.row("无人连接时关闭", snapshot.optBoolean("timeoutAvailable") ? HotspotConfiguration.timeoutName(snapshot.optLong("timeout")) : "由系统管理", R.drawable.ic_ms_alarm, this::timeouts).setTag("hotspot-timeout");
    }
    private TextView subtitle(LinearLayout row) { return (TextView)((LinearLayout)row.getChildAt(1)).getChildAt(1); }
    private void updateNfc() {
        int state=snapshot.optInt("state",-1); nfcTransition=false;
        nfcStatus.setText((state<0 ? "状态未知或正在切换" : state==1 ? "已开启" : "已关闭")+" · 整机开关"); nfcPower.setStateDescription(state<0 ? "未知" : state==1 ? "已开启" : "已关闭");
        if (!nfcTransition) nfcToggle.state(state<0 ? null : state==1); nfcPower.setEnabled(!busy && !nfcTransition); nfcToggle.setEnabled(nfcPower.isEnabled());
        nfcPaymentStatus.setText(snapshot.optString("payment","暂不可用")+" · 更换需系统确认");
        if (nfcSecureToggle!=null) { int secure=snapshot.optInt("secure",-1); nfcSecureToggle.state(secure<0 ? null : secure==1); nfcSecureRow.setEnabled(!busy && state==1 && secure>=0); nfcSecureToggle.setEnabled(nfcSecureRow.isEnabled()); nfcSecureStatus.setText(secure<0 ? "状态未知，请使用系统设置" : secure==1 ? "已开启" : "已关闭"); }
    }
    private void chooseSwitch() {
        clear("选择操作", "switch"); rows.note("当前状态尚未确认，此开关影响整个手机。");
        buttons("开启",() -> run(id,1,""),"关闭",() -> run(id,0,""));
    }
    private void edit() {
        if (busy || snapshot == null || !snapshot.optBoolean("configAvailable")) return;
        try { editBase = new JSONObject(snapshot.toString()); } catch (Exception ignored) { return; }
        revealed = false; clear("名称与密码", "edit");
        rows.note("热点名称"); name = input(editBase.optString("name"), false); name.setTag("hotspot-name");
        rows.note("密码 · " + HotspotConfiguration.securityName(editBase.optInt("security", -1))); password = input(editBase.optString("password"), true); password.setTag("hotspot-password");
        boolean editable = HotspotConfiguration.editablePassword(editBase.optInt("security", -1)); password.setEnabled(editable);
        rows.note(editable ? "8–63 位英文字母、数字或符号；保存时生效。" : "此安全类型的密码请到系统设置修改。此处仍可修改名称。");
        buttons("取消", () -> { clearDraft(); main(); }, "保存", this::saveEdit);
        sheet.footer("返回", this::back);
    }
    private EditText input(String text, boolean secret) {
        EditText input = new EditText(context); input.setSingleLine(true); input.setTextSize(PanelUi.BODY); input.setTextColor(Ui.TEXT); input.setHintTextColor(Ui.MUTED);
        input.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); input.setText(text); sheet.content.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    private void saveEdit() {
        if (busy || editBase == null) return;
        try {
            String title = name.getText().toString(), pass = password.getText().toString(); HotspotConfiguration.validateName(title);
            JSONObject patch = patch(editBase);
            if (!title.equals(editBase.optString("name"))) patch.put("name", title);
            if (HotspotConfiguration.editablePassword(editBase.optInt("security", -1)) && !pass.equals(editBase.optString("password"))) { HotspotConfiguration.validatePassword(pass, editBase.optInt("security")); patch.put("password", pass); }
            if (patch.length() == 2) { clearDraft(); main(); return; }
            hideKeyboard(); prepare(patch);
        } catch (Exception error) { say(error instanceof IllegalArgumentException ? error.getMessage() : "配置无效，请检查输入"); }
    }
    private JSONObject patch(JSONObject base) throws Exception { return new JSONObject().put("revision", base.getString("revision")).put("restart", false); }
    private void prepare(JSONObject patch) throws Exception {
        pending = patch;
        if (snapshot == null || snapshot.optInt("state", -1) < 0) { say("热点状态未知，请返回刷新后再试"); return; }
        if (snapshot.optInt("state") == 1) {
            returnPage = page; clear("应用并重启热点", "restart");
            rows.note("此修改需要重启热点，已连接设备会断开，需要重新连接。");
            buttons("返回修改", this::back, "应用并重启", () -> { try { pending.put("restart", true); run("hotspot_config", 0, pending.toString()); } catch (Exception ignored) { say("配置无效"); } });
        } else run("hotspot_config", 0, patch.toString());
    }
    private void bands() {
        if (snapshot == null) return; clear("热点频段", "bands");
        JSONArray supported = snapshot.optJSONArray("bands");
        if (supported == null || supported.length() == 0) { rows.note("尚未确认可用频段，请刷新或使用系统设置。"); sheet.footer("系统热点设置", this::systemSettings); return; }
        for (int i = 0; i < supported.length(); i++) {
            int band = supported.optInt(i);
            rows.row(HotspotConfiguration.bandName(band), snapshot.optInt("band") == band ? "当前频段" : band == 1 ? "兼容更多设备" : "需连接设备支持", R.drawable.ic_ms_wifi, () -> {
                try { if (snapshot.optInt("band") == band) { main(); return; } prepare(patch(snapshot).put("band", band)); } catch (Exception error) { say("频段配置不可用"); }
            });
        }
    }
    private void timeouts() {
        if (snapshot == null) return; clear("无人连接时关闭", "timeouts");
        if (!snapshot.optBoolean("timeoutAvailable")) { rows.note("此设置由系统管理。"); sheet.footer("系统热点设置", this::systemSettings); return; }
        rows.note("仅在没有设备连接时开始计时。");
        for (long timeout : new long[]{0, 300000, 600000, 1800000, -1}) rows.row(HotspotConfiguration.timeoutName(timeout), snapshot.optLong("timeout") == timeout ? "当前设置" : "", R.drawable.ic_ms_alarm, () -> {
            try { if (snapshot.optLong("timeout") == timeout) { main(); return; } prepare(patch(snapshot).put("timeout", timeout)); } catch (Exception error) { say("自动关闭设置不可用"); }
        });
    }
    private void clients() {
        clear("连接设备", "clients");
        if (snapshot == null || !snapshot.optBoolean("clientsAvailable")) rows.note("设备列表暂不可用，请刷新或在系统设置中查看。");
        else {
            JSONArray clients = snapshot.optJSONArray("clients"); int count = clients == null ? 0 : clients.length();
            rows.note("当前连接 · " + count + " 台");
            for (int i = 0; i < count; i++) { View row = rows.row("设备 " + (i + 1), clients.optString(i), R.drawable.ic_connectivity_devices, () -> { }); row.setClickable(false); }
            rows.note("只显示当前连接，不保存设备历史。");
        }
        sheet.footer("系统设备管理", this::systemSettings);
    }
    private void share() {
        clear("扫码连接", "qr"); revealed = false;
        if (snapshot == null || !snapshot.optBoolean("configAvailable")) { rows.note("热点资料暂不可用，请刷新。"); return; }
        try {
            String text = HotspotConfiguration.qrPayload(snapshot.optString("name"), snapshot.optString("password"), snapshot.optInt("security", -1), snapshot.optBoolean("hidden"));
            BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 192, 192, Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 4));
            int[] pixels = new int[192 * 192]; for (int y = 0; y < 192; y++) for (int x = 0; x < 192; x++) pixels[y * 192 + x] = matrix.get(x, y) ? android.graphics.Color.BLACK : android.graphics.Color.WHITE;
            qr = Bitmap.createBitmap(pixels, 192, 192, Bitmap.Config.ARGB_8888); qrView = new ImageView(context); qrView.setImageBitmap(qr); qrView.setContentDescription("当前热点连接二维码"); qrView.setTag("hotspot-qr");
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(Ui.dp(context, 154), Ui.dp(context, 154)); size.gravity = android.view.Gravity.CENTER_HORIZONTAL; sheet.content.addView(qrView, size);
            rows.note(snapshot.optString("name")); rows.note(snapshot.optInt("state", -1) == 1 ? "使用另一台设备扫描连接" : "热点尚未确认开启，开启后才能连接。");
        } catch (Exception error) { rows.note(error instanceof IllegalArgumentException ? error.getMessage() : "二维码暂不可用，请使用系统分享"); sheet.footer("系统热点设置", this::systemSettings); }
    }
    private void buttons(String first, Runnable cancel, String second, Runnable confirm) {
        LinearLayout actions = Ui.row(context); actions.addView(PanelUi.button(context, first, cancel), new LinearLayout.LayoutParams(0, -2, 1)); actions.addView(PanelUi.button(context, second, confirm), new LinearLayout.LayoutParams(0, -2, 1)); sheet.content.addView(actions);
    }
    private void run(String operation, int value, String input) {
        if (busy || closed || nfcTransition) return; if (!live()) { say("所选外屏不可用，请解锁后重试"); return; }
        if (operation.equals(id)) { GlassToggle toggle=sheet.content.findViewWithTag("connectivity-toggle"); if (toggle!=null) toggle.pending(value==1); if (id.equals("nfc") && nfcToggle!=null) nfcTransition=true; }
        if (operation.equals("nfc_secure") && nfcSecureToggle!=null) nfcSecureToggle.pending(value==1);
        busy = true; int operationGeneration = ++readGeneration; reading = false; enabled(sheet.content, false); back.setEnabled(false); refresh.setEnabled(false); say("正在执行…");
        owner.connectivityAction(operation, value, input, result -> {
            if (!live() || operationGeneration != readGeneration) return; busy = false; enabled(sheet.content, true); back.setEnabled(true); refresh.setEnabled(true);
            if (nfcTransition && nfcPower!=null) { nfcPower.setEnabled(false); nfcToggle.setEnabled(false); }
            if (result.ok) { clearDraft(); revealed = false; page = "main"; read(4); }
            else {
                nfcTransition=false; if (nfcPower!=null && snapshot!=null) updateNfc();
                say(result.message);
                boolean reload = false; try { reload = !result.output.isEmpty() && new JSONObject(result.output).optBoolean("reload"); } catch (Exception ignored) { }
                if (reload) { operationNotice = result.message; clearDraft(); revealed = false; page = "main"; read(4); }
                else if (readPending) read(4);
            }
        });
    }
    private void enabled(View view, boolean enabled) { if (view instanceof Button || view instanceof EditText || view.isClickable()) view.setEnabled(enabled); if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) enabled(group.getChildAt(i), enabled); }
    private boolean dirty() { return editBase != null && name != null && password != null && (!name.getText().toString().equals(editBase.optString("name")) || !password.getText().toString().equals(editBase.optString("password"))); }
    private boolean canClose() {
        if (busy) return true;
        if (dirty() && !page.equals("discard")) { returnPage = page; discard(); return false; }
        return true;
    }
    private void discard() { hideKeyboard(); clear("放弃未保存的修改？", "discard"); rows.note("已保存的热点配置不会改变。"); buttons("继续编辑", this::restoreEdit, "放弃修改", () -> { clearDraft(); main(); }); }
    private void restoreEdit() {
        if (editBase == null) { main(); return; }
        String title = name == null ? editBase.optString("name") : name.getText().toString(), pass = password == null ? editBase.optString("password") : password.getText().toString(); JSONObject original = editBase;
        JSONObject current = snapshot; snapshot = original; edit(); snapshot = current; name.setText(title); password.setText(pass);
    }
    private void back() {
        if (busy) return;
        if (page.equals("main")) sheet.close();
        else if (page.equals("edit") && dirty()) discard();
        else if (page.equals("discard")) restoreEdit();
        else if (page.equals("restart")) { if (returnPage.equals("edit")) restoreEdit(); else if (returnPage.equals("bands")) bands(); else timeouts(); }
        else { clearDraft(); revealed = false; main(); }
    }
    private void systemSettings() { owner.launch(new Intent(id.equals("nfc") ? Settings.ACTION_NFC_SETTINGS : "android.settings.TETHER_SETTINGS")); }
    private void hideKeyboard() { context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(sheet.getWindowToken(), 0); }
    private void clearDraft() { if (name != null) name.setText(""); if (password != null) password.setText(""); name = null; password = null; editBase = null; pending = null; }
    private void releaseQr() { if (qrView != null) qrView.setImageDrawable(null); qrView = null; if (qr != null) qr.recycle(); qr = null; }
    @Override public void close() { if (closed) return; closed = true; readGeneration++; owner.main.removeCallbacks(readLater); bridge.removeConnectivityObserver(changed); bridge.removeObserver(changed); releaseQr(); clearDraft(); snapshot = null; revealed = false; hideKeyboard(); sheet.onBack(null); sheet.onCloseRequest(null); }
}
