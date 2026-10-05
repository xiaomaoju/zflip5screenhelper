package io.github.flipcover.controls;

/** Test APK only: persistent root and detail pages report task and instance identity. */
public class AppLaunchFixtureActivity extends android.app.Activity {
    private final String instance = java.util.UUID.randomUUID().toString();
    @Override public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) { finish(); return; }
        android.widget.TextView text = new android.widget.TextView(this); text.setText(getClass().getSimpleName() + " · " + getTaskId()); setContentView(text);
    }
    @Override protected void onResume() {
        super.onResume();
        if (!isFinishing()) sendBroadcast(new android.content.Intent("fixture.APP_REUSE_RESULT").setPackage(getPackageName().substring(0, getPackageName().length() - 5)).putExtra("display", getDisplay().getDisplayId()).putExtra("task", getTaskId()).putExtra("instance", instance).putExtra("detail", this instanceof Detail));
    }
    public static final class Detail extends AppLaunchFixtureActivity { }
}
