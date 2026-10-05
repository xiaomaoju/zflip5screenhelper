package io.github.flipcover.controls;

/** Exists only in the emulator test APK; no production component is exposed. */
public final class RecentTaskFixtureActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) { finish(); return; }
        if (getIntent().getBooleanExtra("secure", false)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        android.widget.TextView text = new android.widget.TextView(this); text.setText("外屏任务接口验证\n窗口 " + getTaskId()); text.setTextSize(24); text.setTextColor(android.graphics.Color.WHITE); text.setBackgroundColor(0xFF243952); text.setGravity(android.view.Gravity.CENTER); setContentView(text);
    }
}
