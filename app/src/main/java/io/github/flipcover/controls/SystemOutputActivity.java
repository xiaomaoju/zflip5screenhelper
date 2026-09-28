package io.github.flipcover.controls;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaRouter2;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;

/** Foreground-only system output entry. Never silently opens on display zero. */
public final class SystemOutputActivity extends Activity {
    private boolean requested;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); int expected = getIntent().getIntExtra("display", -1);
        if (getDisplay() == null || expected <= 0 || getDisplay().getDisplayId() != expected) { finish(); return; }
        LinearLayout body = Ui.column(this); body.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12)); body.setBackgroundColor(Ui.BACKGROUND);
        Ui.add(body, Ui.heading(this, "系统媒体输出", 16)); Ui.add(body, Ui.text(this, "由系统选择输出设备", 12, Ui.MUTED)); Ui.add(body, Ui.button(this, "重新打开选择器", this::showOutput));
        Ui.add(body, Ui.button(this, "声音设置", () -> { try { startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(expected).toBundle()); } catch (RuntimeException ignored) { } }));
        Ui.add(body, Ui.button(this, "关闭", this::finish)); setContentView(body);
    }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused && !isFinishing() && !requested) { requested = true; showOutput(); } }
    private void showOutput() {
        boolean shown = false; if (Build.VERSION.SDK_INT >= 34) try { shown = MediaRouter2.getInstance(this).showSystemOutputSwitcher(); } catch (RuntimeException ignored) { }
        if (!shown) android.widget.Toast.makeText(this, "系统未接受输出选择，可进入声音设置", android.widget.Toast.LENGTH_SHORT).show();
    }
}
