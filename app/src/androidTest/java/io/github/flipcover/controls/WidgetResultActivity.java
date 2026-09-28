package io.github.flipcover.controls;

/** A separate-package caller simulating Samsung's startActivityForResult contract. */
public final class WidgetResultActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        if (saved == null) startActivityForResult(new android.content.Intent().setComponent(new android.content.ComponentName(getIntent().getStringExtra("target_package"), "io.github.flipcover.controls.NativeWidgetActivity")).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, getIntent().getIntExtra("outer", -1)), 91);
    }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        sendBroadcast(new android.content.Intent("fixture.CONFIG_RESULT").setPackage(getIntent().getStringExtra("target_package")).putExtra("result", result).putExtra("outer", data == null ? -1 : data.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1))); finish();
    }
}
