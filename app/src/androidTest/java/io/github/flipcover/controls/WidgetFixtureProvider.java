package io.github.flipcover.controls;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Different-UID widget provider: proves resources and PendingIntents survive the relay. */
public final class WidgetFixtureProvider extends AppWidgetProvider {
    @Override public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if ("fixture.UPDATE".equals(intent.getAction()) || "fixture.CLICK".equals(intent.getAction())) {
            int value = intent.getIntExtra("value", "fixture.CLICK".equals(intent.getAction()) ? 7 : 1);
            for (int id : AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, WidgetFixtureProvider.class))) if (!intent.hasExtra("only_id") || id == intent.getIntExtra("only_id", -1)) update(context, id, value);
        }
    }
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { for (int id : ids) update(context, id, 1); }
    private void update(Context context, int id, int value) {
        boolean fittedFixture = value == 11 || value == 12;
        int layout = value == 11 ? io.github.flipcover.controls.test.R.layout.widget_fixture_fixed : value == 12 ? io.github.flipcover.controls.test.R.layout.widget_fixture_artwork : io.github.flipcover.controls.test.R.layout.widget_fixture;
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        views.setTextViewText(io.github.flipcover.controls.test.R.id.fixture_text, "跨应用更新 " + value);
        PendingIntent click = PendingIntent.getBroadcast(context, id, new Intent(context, WidgetFixtureProvider.class).setAction("fixture.CLICK"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(io.github.flipcover.controls.test.R.id.fixture_button, click);
        if (android.os.Build.VERSION.SDK_INT >= 31 && value == 12) views.setViewLayoutHeight(io.github.flipcover.controls.test.R.id.fixture_artwork, 120, android.util.TypedValue.COMPLEX_UNIT_DIP);
        if (android.os.Build.VERSION.SDK_INT >= 31 && !fittedFixture) {
            RemoteViews row = new RemoteViews(context.getPackageName(), android.R.layout.simple_list_item_1); row.setTextViewText(android.R.id.text1, "列表内容 " + value);
            views.setRemoteAdapter(io.github.flipcover.controls.test.R.id.fixture_list, new RemoteViews.RemoteCollectionItems.Builder().addItem(1, row).setHasStableIds(true).build());
        }
        if (android.os.Build.VERSION.SDK_INT >= 31 && !fittedFixture) {
            RemoteViews small = new RemoteViews(views), large = new RemoteViews(views);
            small.setTextViewText(io.github.flipcover.controls.test.R.id.fixture_variant, "SMALL");
            large.setTextViewText(io.github.flipcover.controls.test.R.id.fixture_variant, "LARGE");
            views = new RemoteViews(java.util.Map.of(new android.util.SizeF(80, 80), small, new android.util.SizeF(260, 260), large));
        }
        AppWidgetManager.getInstance(context).updateAppWidget(id, views);
    }
}
