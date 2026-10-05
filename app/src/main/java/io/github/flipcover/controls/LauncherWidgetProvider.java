package io.github.flipcover.controls;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** A standalone Samsung card; never allocates or replaces a combination-card slot. */
public final class LauncherWidgetProvider extends AppWidgetProvider {
    static final String ACTION = "io.github.flipcover.controls.LAUNCHER_WIDGET";
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { CoverApp.launcherWidgets(context).refresh(); }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) { CoverApp.launcherWidgets(context).optionsChanged(id, options); }
    @Override public void onDeleted(Context context, int[] ids) { for (int id : ids) CoverApp.launcherWidgets(context).remove(id); }
    @Override public void onDisabled(Context context) { CoverApp.launcherWidgets(context).refresh(); }
    @Override public void onReceive(Context context, Intent intent) {
        if (ACTION.equals(intent.getAction())) CoverApp.launcherWidgets(context).action(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1), intent.getIntExtra("display", -1), intent.getStringExtra("operation"), intent.getStringExtra("item"));
        else super.onReceive(context, intent);
    }
}
