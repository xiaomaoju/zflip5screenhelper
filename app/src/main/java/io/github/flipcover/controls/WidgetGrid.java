package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** One 4x4 card. Placement is independent of pixels, providers and Android lifecycle. */
final class WidgetGrid {
    static final int SIDE = 4;
    record Item(int id, int x, int y, int width, int height) {
        int cells() { return width * height; }
        String sizeLabel() { return width + "×" + height; }
        Item at(int x, int y) { return new Item(id, x, y, width, height); }
    }
    static boolean valid(List<Item> items) {
        if (items.size() > 16) return false;
        HashSet<Integer> ids = new HashSet<>(); int occupied = 0;
        for (Item item : items) { int mask = mask(item); if (item.id <= 0 || !ids.add(item.id) || mask == 0 || (occupied & mask) != 0) return false; occupied |= mask; }
        return true;
    }
    static int mask(Item item) {
        if (item.x < 0 || item.y < 0 || item.width < 1 || item.height < 1 || item.width > 4 || item.height > 4 || item.x + item.width > 4 || item.y + item.height > 4) return 0;
        int result = 0; for (int y = item.y; y < item.y + item.height; y++) for (int x = item.x; x < item.x + item.width; x++) result |= 1 << (y * 4 + x); return result;
    }
    static boolean fits(List<Item> items, Item candidate) {
        int mask = mask(candidate); if (mask == 0) return false;
        for (Item item : items) if (item.id != candidate.id && (mask(item) & mask) != 0) return false;
        return true;
    }
    static Item find(List<Item> items, int id, int width, int height) {
        if (width < 1 || width > SIDE || height < 1 || height > SIDE) return null;
        for (int y = 0; y <= 4 - height; y++) for (int x = 0; x <= 4 - width; x++) { Item candidate = new Item(id, x, y, width, height); if (fits(items, candidate)) return candidate; }
        return null;
    }
    static List<Item> replace(List<Item> items, Item replacement) {
        List<Item> result = new ArrayList<>(); boolean found = false;
        for (Item item : items) { result.add(item.id == replacement.id ? replacement : item); found |= item.id == replacement.id; }
        if (!found) result.add(replacement); return result;
    }
    static int used(List<Item> items) { return items.stream().mapToInt(Item::cells).sum(); }
    static int[] pack(List<Item> items) { int[] result = new int[items.size() * 5]; int i = 0; for (Item item : items) { result[i++] = item.id; result[i++] = item.x; result[i++] = item.y; result[i++] = item.width; result[i++] = item.height; } return result; }
    static List<Item> unpack(int[] values) {
        if (values == null || values.length % 5 != 0 || values.length > 80) return List.of();
        List<Item> result = new ArrayList<>(); for (int i = 0; i < values.length; i += 5) result.add(new Item(values[i], values[i + 1], values[i + 2], values[i + 3], values[i + 4]));
        return valid(result) ? List.copyOf(result) : List.of();
    }
}
