package io.github.flipcover.controls;

import android.content.ComponentName;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Portable provider/geometry descriptions only. Never contains host or widget instance IDs. */
final class WidgetTemplates {
    static final int MAX_BYTES = 262144;
    record Entry(String provider, int x, int y, int width, int height) {
        WidgetGrid.Item placement(int id) { return new WidgetGrid.Item(id, x, y, width, height); }
    }
    record Card(int slot, List<Entry> items) {
        Card { items = List.copyOf(items); }
    }
    static List<Card> read(JSONArray source) throws JSONException {
        if (source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("小组件模板超过256 KiB，请清理不再需要的导入模板");
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject card = source.getJSONObject(i); int slot = integer(card.get("slot"));
            if (slot < 1 || slot > 6) throw new IllegalArgumentException("小组件卡片编号无效");
            JSONArray items = card.getJSONArray("items"); if (items.length() > 16) throw new IllegalArgumentException("卡片组件过多");
            List<Entry> entries = new ArrayList<>(); List<WidgetGrid.Item> grid = new ArrayList<>();
            for (int j = 0; j < items.length(); j++) {
                JSONObject item = items.getJSONObject(j); Object raw = item.get("provider");
                if (!(raw instanceof String name) || name.length() > 512) throw new IllegalArgumentException("小组件提供方无效");
                ComponentName component = ComponentName.unflattenFromString(name);
                if (component == null || !component.getPackageName().matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") || !component.getClassName().matches("[A-Za-z0-9_.$]+")) throw new IllegalArgumentException("小组件提供方无效");
                Entry entry = new Entry(component.flattenToString(), integer(item.get("x")), integer(item.get("y")), integer(item.get("width")), integer(item.get("height")));
                entries.add(entry); grid.add(entry.placement(j + 1));
            }
            if (!WidgetGrid.valid(grid)) throw new IllegalArgumentException("小组件模板格位越界或重叠");
            cards.add(new Card(slot, entries));
        }
        return List.copyOf(cards);
    }
    static JSONArray json(List<Card> cards) throws JSONException {
        JSONArray result = new JSONArray();
        for (Card card : cards) {
            JSONArray items = new JSONArray();
            for (Entry item : card.items()) items.put(new JSONObject().put("provider", item.provider()).put("x", item.x()).put("y", item.y()).put("width", item.width()).put("height", item.height()));
            result.put(new JSONObject().put("slot", card.slot()).put("items", items));
        }
        return result;
    }
    static List<Card> saved(Prefs prefs) {
        try { return read(new JSONArray(prefs.data.getString("widget_templates", "[]"))); }
        catch (JSONException | IllegalArgumentException error) { throw new IllegalStateException("小组件模板无法读取，请重新导入有效配置", error); }
    }
    private static int integer(Object raw) {
        if (!(raw instanceof Number value) || value.doubleValue() != value.intValue()) throw new IllegalArgumentException("小组件格位必须是整数");
        return value.intValue();
    }
}
