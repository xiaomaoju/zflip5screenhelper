package io.github.flipcover.controls;

import android.content.Context;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Immutable developer configuration packaged from the root app-config.json. */
final class AppConfig {
    record Update(URI catalogUri, int connectTimeoutMs, int readTimeoutMs, int maxCatalogBytes, long maxApkBytes, int maxRedirects, int cacheRetentionHours) { }
    final Update update;
    private static AppConfig current;
    private AppConfig(JSONObject source) throws Exception {
        integer(source, "schemaVersion", 1, 1);
        JSONObject value = source.getJSONObject("update");
        update = new Update(UpdateCatalog.catalogUri(text(value, "catalogUrl")), (int) integer(value, "connectTimeoutMs", 1, 120000), (int) integer(value, "readTimeoutMs", 1, 120000), (int) integer(value, "maxCatalogBytes", 1, 1048576), integer(value, "maxApkBytes", 1, 536870912), (int) integer(value, "maxRedirects", 0, 10), (int) integer(value, "cacheRetentionHours", 1, 168));
    }
    static synchronized AppConfig load(Context context) throws Exception {
        if (current == null) {
            try (InputStream stream = context.getAssets().open("app-config.json")) {
                byte[] encoded = UpdateTransfer.read(stream, 1048576);
                current = new AppConfig(new JSONObject(new String(encoded, StandardCharsets.UTF_8)));
            }
        }
        return current;
    }
    static long integer(JSONObject source, String key, long minimum, long maximum) throws Exception {
        Object raw = source.get(key);
        if (!(raw instanceof Number value) || value.doubleValue() != value.longValue() || value.longValue() < minimum || value.longValue() > maximum) throw new IllegalArgumentException("配置数字无效：" + key);
        return value.longValue();
    }
    static String text(JSONObject source, String key) throws Exception {
        Object raw = source.get(key);
        if (!(raw instanceof String value)) throw new IllegalArgumentException("配置文字无效：" + key);
        return value;
    }
}
