package io.github.flipcover.controls;

import android.app.Application;

public final class CoverApp extends Application {
    public ShizukuBridge bridge;
    private AppCatalogCache catalog;
    private NativeWidgetBridge widgets;
    private LauncherWidgetBridge launcherWidgets;
    private InputDevices inputs;
    private final AppLauncher launcher = new AppLauncher();
    private final RecentTasks.Locks taskLocks = new RecentTasks.Locks();
    private android.content.res.Configuration catalogConfiguration;
    @Override public void onCreate() {
        super.onCreate();
        bridge = new ShizukuBridge(this);
        catalog = new AppCatalogCache(this);
        catalogConfiguration = new android.content.res.Configuration(getResources().getConfiguration());
        catalog.warm();
        new Prefs(this).migrateInputTile();
        widgets(this).refresh();
        launcherWidgets(this).refresh();
    }
    static NativeWidgetBridge widgets(android.content.Context context) {
        CoverApp app = (CoverApp) context.getApplicationContext();
        if (app.widgets == null) app.widgets = new NativeWidgetBridge(app);
        return app.widgets;
    }
    static AppCatalogCache catalog(android.content.Context context) { return ((CoverApp) context.getApplicationContext()).catalog; }
    static AppLauncher launcher(android.content.Context context) { return ((CoverApp) context.getApplicationContext()).launcher; }
    static LauncherWidgetBridge launcherWidgets(android.content.Context context) {
        CoverApp app = (CoverApp) context.getApplicationContext();
        if (app.launcherWidgets == null) app.launcherWidgets = new LauncherWidgetBridge(app);
        return app.launcherWidgets;
    }
    static RecentTasks.Locks taskLocks(android.content.Context context) { return ((CoverApp) context.getApplicationContext()).taskLocks; }
    static InputDevices inputs(android.content.Context context) { CoverApp app = (CoverApp) context.getApplicationContext(); if (app.inputs == null) app.inputs = new InputDevices(app); return app.inputs; }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        int changes = catalogConfiguration.updateFrom(configuration);
        if ((changes & (android.content.pm.ActivityInfo.CONFIG_LOCALE | android.content.pm.ActivityInfo.CONFIG_DENSITY | android.content.pm.ActivityInfo.CONFIG_UI_MODE)) != 0) catalog.invalidate();
    }
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) { catalog.trimMemory(); if (widgets != null) widgets.trim(); if (launcherWidgets != null) launcherWidgets.trim(); } }
    @Override public void onLowMemory() { super.onLowMemory(); catalog.trimMemory(); if (widgets != null) widgets.trim(); if (launcherWidgets != null) launcherWidgets.trim(); }
    public static ShizukuBridge bridge(android.content.Context context) {
        return ((CoverApp) context.getApplicationContext()).bridge;
    }
}
