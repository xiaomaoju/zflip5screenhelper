package io.github.flipcover.controls;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Samsung owns the outer card; NativeWidgetBridge owns the explicitly bound inner widget. */
public class NativeWidgetProvider extends AppWidgetProvider {
    public static final class Slot2 extends NativeWidgetProvider { }
    public static final class Slot3 extends NativeWidgetProvider { }
    public static final class Slot4 extends NativeWidgetProvider { }
    public static final class Slot5 extends NativeWidgetProvider { }
    public static final class Slot6 extends NativeWidgetProvider { }
    static final Class<?>[] TYPES = {NativeWidgetProvider.class, Slot2.class, Slot3.class, Slot4.class, Slot5.class, Slot6.class};
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { CoverApp.widgets(context).refresh(); }
    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) { CoverApp.widgets(context).resize(id); }
    @Override public void onDeleted(Context context, int[] ids) { for (int id : ids) CoverApp.widgets(context).remove(id); }
    @Override public void onDisabled(Context context) { CoverApp.widgets(context).refresh(); }
    @Override public void onRestored(Context context, int[] oldIds, int[] newIds) {
        // Bindings are local system identities and cannot be copied between installations.
        for (int id : oldIds) CoverApp.widgets(context).remove(id);
        CoverApp.widgets(context).refresh();
    }
}
