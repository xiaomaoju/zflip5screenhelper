package io.github.flipcover.controls;

/** Test APK only: reports the actual display reached by a native card click. */
public final class LauncherLaunchFixtureActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        if (android.os.Build.HARDWARE.equals("ranchu") || android.os.Build.HARDWARE.equals("goldfish")) sendBroadcast(new android.content.Intent("fixture.LAUNCHER_RESULT").setPackage(getPackageName().substring(0, getPackageName().length() - 5)).putExtra("display", getDisplay().getDisplayId()));
        finish();
    }
}
