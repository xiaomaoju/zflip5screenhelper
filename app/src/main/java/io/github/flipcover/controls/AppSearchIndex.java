package io.github.flipcover.controls;

import me.majiajie.tinypinyin.Pinyin;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Local bounded search keys; no history or network. Aliases also disambiguate polyphonic names. */
final class AppSearchIndex {
    private final Map<String, String> keys = new LinkedHashMap<>() { @Override protected boolean removeEldestEntry(Map.Entry<String, String> entry) { return size() > AppWorkspaceLayout.MAX_APPS; } };
    static String romanize(String label) {
        StringBuilder full = new StringBuilder(), initials = new StringBuilder();
        for (int i = 0; i < label.length(); i++) { char c = label.charAt(i); String syllable = Pinyin.toPinyin(c).toLowerCase(Locale.ROOT); full.append(syllable); if (!syllable.isEmpty()) initials.append(syllable.charAt(0)); }
        return full + " " + initials;
    }
    boolean matches(AppCatalogCache.Entry entry, String alias, String query) {
        if (query.isEmpty() || entry.searchKey().contains(query) || alias.toLowerCase(Locale.ROOT).contains(query)) return true;
        String label = entry.label() + " " + alias; String key = keys.get(label); if (key == null) { key = romanize(label); keys.put(label, key); } return key.contains(query);
    }
    void clear() { keys.clear(); }
}
