package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable ownership tree and real occupied cells. Projections never mutate the saved layout. */
final class AppWorkspaceLayout {
    static final int MAX_APPS = 1000, MAX_SLOTS = 4096;
    record Folder(String name, List<String> members, int color) {
        Folder(String name, List<String> members) { this(name, members, 0); }
        Folder {
            if (name == null || name.trim().isEmpty() || name.codePointCount(0, name.length()) > 24 || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("文件夹名称需为1–24个字符");
            name = name.trim(); members = List.copyOf(members);
            if (members.size() < 2 || members.size() > 9 || new HashSet<>(members).size() != members.size()) throw new IllegalArgumentException("文件夹需有2–9个不同应用");
            if (color < 0 || color > 5) throw new IllegalArgumentException("文件夹颜色无效");
        }
    }
    private final Map<String, Integer> positions;
    private final Map<String, Folder> folders;
    private final int columns, rows;
    AppWorkspaceLayout() { this(Map.of()); }
    AppWorkspaceLayout(Map<String, Integer> source) { this(source, Map.of(), 5, 3); }
    AppWorkspaceLayout(Map<String, Integer> source, Map<String, Folder> folders, int columns, int rows) {
        if (columns < 1 || columns > 12 || rows < 1 || rows > 12 || !source.keySet().containsAll(folders.keySet())) throw new IllegalArgumentException("文件夹布局规格无效");
        this.columns = columns; this.rows = rows; this.folders = Map.copyOf(folders);
        if (source.size() > MAX_APPS) throw new IllegalArgumentException("应用布局过大");
        boolean[] used = new boolean[MAX_SLOTS]; Set<String> apps = new HashSet<>();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String id = entry.getKey(); Integer slot = entry.getValue();
            if (id == null || id.isEmpty() || slot == null || !fits(used, slot, span(id), columns, rows)) throw new IllegalArgumentException("应用格位越界或重叠");
            occupy(used, slot, span(id), columns);
            if (folders.containsKey(id)) {
                for (String member : folders.get(id).members()) if (member.isEmpty() || folders.containsKey(member) || !apps.add(member)) throw new IllegalArgumentException("文件夹不能嵌套或重复应用");
            } else if (!apps.add(id)) throw new IllegalArgumentException("应用重复");
        }
        if (apps.size() > MAX_APPS) throw new IllegalArgumentException("应用布局过大");
        positions = Map.copyOf(source);
    }
    Map<String, Folder> folders() { return folders; }
    Folder folder(String id) { return id == null ? null : folders.get(id); }
    int columns() { return columns; }
    int rows() { return rows; }
    int span(String id) { return folder(id) != null && columns >= 2 && rows >= 2 ? 2 : 1; }
    Set<String> apps() { Set<String> result = new HashSet<>(); for (String id : positions.keySet()) { Folder folder = folder(id); if (folder == null) result.add(id); else result.addAll(folder.members()); } return result; }
    String parent(String app) { if (app == null) return null; for (String id : folders.keySet()) if (folder(id).members().contains(app)) return id; return null; }
    String at(int slot) { for (String id : ordered()) { int start = slot(id), delta = slot - start; if (delta >= 0 && delta / columns < span(id) && delta % columns < span(id)) return id; } return null; }
    Map<String, Integer> positions() { return positions; }
    int slot(String id) { return id == null ? -1 : positions.getOrDefault(id, -1); }
    int size() { return positions.size(); }
    int extent() { int end = 0; for (String id : positions.keySet()) end = Math.max(end, slot(id) + (span(id) - 1) * columns + span(id)); return end; }
    int pages(int capacity) { return Math.max(1, (extent() + Math.max(1, capacity) - 1) / Math.max(1, capacity)); }
    List<String> ordered() { List<String> result = new ArrayList<>(positions.keySet()); result.sort((a, b) -> Integer.compare(slot(a), slot(b))); return result; }
    static AppWorkspaceLayout sequential(List<String> ids) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String id : ids) if (!result.containsKey(id)) result.put(id, result.size());
        return new AppWorkspaceLayout(result);
    }
    AppWorkspaceLayout compact() { return arrange(positions, folders, columns, rows, null, true); }
    AppWorkspaceLayout project(int columns, int rows) { return this.columns == columns && this.rows == rows ? this : arrange(positions, folders, columns, rows, null, false); }
    private static boolean fits(boolean[] used, int slot, int span, int columns, int rows) {
        if (slot < 0 || slot >= MAX_SLOTS || slot % columns + span > columns || slot % (columns * rows) / columns + span > rows) return false;
        for (int y = 0; y < span; y++) for (int x = 0; x < span; x++) { int cell = slot + y * columns + x; if (cell >= MAX_SLOTS || used[cell]) return false; }
        return true;
    }
    private static void occupy(boolean[] used, int slot, int span, int columns) { for (int y = 0; y < span; y++) for (int x = 0; x < span; x++) used[slot + y * columns + x] = true; }
    private static int vacancy(boolean[] used, int start, int span, int columns, int rows) {
        start = Math.max(0, Math.min(MAX_SLOTS - 1, start));
        for (int distance = 0; distance < MAX_SLOTS; distance++) {
            if (fits(used, start + distance, span, columns, rows)) return start + distance;
            if (distance > 0 && fits(used, start - distance, span, columns, rows)) return start - distance;
        }
        throw new IllegalArgumentException("没有足够的桌面空间");
    }
    private static int inward(int slot, int span, int columns, int rows) { int capacity = columns * rows, local = slot % capacity; return slot / capacity * capacity + Math.min(local / columns, rows - span) * columns + Math.min(local % columns, columns - span); }
    private static AppWorkspaceLayout arrange(Map<String, Integer> desired, Map<String, Folder> folders, int columns, int rows, String priority, boolean compact) {
        List<String> order = new ArrayList<>(desired.keySet()); order.sort((a, b) -> Integer.compare(desired.get(a), desired.get(b)));
        if (priority != null) { order.remove(priority); order.add(0, priority); }
        Map<String, Integer> result = new LinkedHashMap<>(); boolean[] used = new boolean[MAX_SLOTS]; List<String> pending = new ArrayList<>();
        for (String id : order) {
            int span = folders.containsKey(id) && columns >= 2 && rows >= 2 ? 2 : 1;
            int slot = compact ? vacancy(used, 0, span, columns, rows) : inward(desired.get(id), span, columns, rows);
            if (fits(used, slot, span, columns, rows)) { result.put(id, slot); occupy(used, slot, span, columns); } else pending.add(id);
        }
        for (String id : pending) { int span = folders.containsKey(id) && columns >= 2 && rows >= 2 ? 2 : 1; int slot = vacancy(used, desired.get(id), span, columns, rows); result.put(id, slot); occupy(used, slot, span, columns); }
        return new AppWorkspaceLayout(result, folders, columns, rows);
    }
    /** Called only for a complete, current catalog; a transient loading failure cannot delete cells. */
    AppWorkspaceLayout reconcile(List<String> catalog, boolean compact) {
        if (catalog.size() > MAX_APPS) throw new IllegalArgumentException("应用目录过大");
        if (!folders.isEmpty()) {
            Set<String> available = new HashSet<>(catalog); Map<String, Integer> result = new LinkedHashMap<>(); Map<String, Folder> kept = new LinkedHashMap<>(); Set<String> owned = new HashSet<>();
            for (String id : ordered()) {
                Folder folder = folder(id);
                if (folder == null) { if (available.contains(id)) { result.put(id, slot(id)); owned.add(id); } }
                else { List<String> members = new ArrayList<>(folder.members()); members.removeIf(app -> !available.contains(app)); owned.addAll(members); if (members.size() == 1) result.put(members.get(0), slot(id)); else if (members.size() > 1) { result.put(id, slot(id)); kept.put(id, new Folder(folder.name(), members, folder.color())); } }
            }
            int next = result.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
            for (String id : catalog) if (owned.add(id)) result.put(id, Math.min(MAX_SLOTS - 1, next++));
            return arrange(result, kept, columns, rows, null, compact);
        }
        Set<String> available = new HashSet<>(catalog); Map<String, Integer> result = new LinkedHashMap<>();
        for (String id : ordered()) if (available.contains(id)) result.put(id, slot(id));
        Set<Integer> used = new HashSet<>(result.values()); int next = result.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        for (String id : catalog) if (!result.containsKey(id)) {
            if (next >= MAX_SLOTS) next = 0;
            while (used.contains(next)) next++;
            result.put(id, next); used.add(next++);
        }
        AppWorkspaceLayout layout = new AppWorkspaceLayout(result, Map.of(), columns, rows); return compact ? layout.compact() : layout;
    }
    AppWorkspaceLayout move(String id, int target, boolean compact) {
        int source = slot(id);
        if (source < 0 || target < 0 || target >= MAX_SLOTS || source == target) return this;
        if (!folders.isEmpty()) { Map<String, Integer> result = new LinkedHashMap<>(positions); result.put(id, target); AppWorkspaceLayout moved = arrange(result, folders, columns, rows, id, false); return compact ? moved.compact() : moved; }
        if (compact) {
            List<String> order = ordered(); order.remove(id); order.add(Math.min(target, order.size()), id); return sequential(order).project(columns, rows);
        }
        Map<String, Integer> result = new LinkedHashMap<>(positions); result.remove(id);
        Map<Integer, String> occupied = new LinkedHashMap<>(); for (Map.Entry<String, Integer> entry : result.entrySet()) occupied.put(entry.getValue(), entry.getKey());
        int empty = target;
        if (occupied.containsKey(target)) {
            for (int distance = 1; distance < MAX_SLOTS; distance++) {
                if (target + distance < MAX_SLOTS && !occupied.containsKey(target + distance)) { empty = target + distance; break; }
                if (target - distance >= 0 && !occupied.containsKey(target - distance)) { empty = target - distance; break; }
            }
            int step = empty > target ? -1 : 1;
            for (int cell = empty; cell != target; cell += step) result.put(occupied.get(cell + step), cell);
        }
        result.put(id, target); return new AppWorkspaceLayout(result, Map.of(), columns, rows);
    }
    AppWorkspaceLayout merge(String app, String target) {
        if (columns < 2 || rows < 2) throw new IllegalArgumentException("当前空间不足以创建或合并大文件夹");
        if (!apps().contains(app) || !positions.containsKey(target) || app.equals(target) || target.equals(parent(app))) throw new IllegalArgumentException("不能合并此应用");
        Folder existing = folder(target); if (existing != null && existing.members().size() == 9) throw new IllegalArgumentException("文件夹已满 9/9");
        Map<String, Integer> places = new LinkedHashMap<>(positions); Map<String, Folder> groups = new LinkedHashMap<>(folders); detach(app, places, groups);
        String id = existing == null ? "folder:" + java.util.UUID.randomUUID() : target;
        List<String> members = existing == null ? new ArrayList<>(List.of(target)) : new ArrayList<>(existing.members()); members.add(app);
        int anchor = places.remove(target); places.put(id, anchor); groups.put(id, new Folder(existing == null ? "文件夹" : existing.name(), members, existing == null ? 0 : existing.color()));
        return arrange(places, groups, columns, rows, id, false);
    }
    private void detach(String app, Map<String, Integer> places, Map<String, Folder> groups) {
        String parent = parent(app);
        if (parent == null) places.remove(app);
        else { Folder old = groups.get(parent); List<String> members = new ArrayList<>(old.members()); members.remove(app); if (members.size() == 1) { int anchor = places.remove(parent); groups.remove(parent); places.put(members.get(0), anchor); } else groups.put(parent, new Folder(old.name(), members, old.color())); }
    }
    AppWorkspaceLayout extract(String app, int target) {
        if (parent(app) == null || target < 0 || target >= MAX_SLOTS) return this;
        Map<String, Integer> places = new LinkedHashMap<>(positions); Map<String, Folder> groups = new LinkedHashMap<>(folders); detach(app, places, groups); places.put(app, target);
        return arrange(places, groups, columns, rows, app, false);
    }
    AppWorkspaceLayout dissolve(String id) {
        Folder folder = folder(id); if (folder == null) return this;
        Map<String, Integer> places = new LinkedHashMap<>(positions); Map<String, Folder> groups = new LinkedHashMap<>(folders); int anchor = places.remove(id); groups.remove(id);
        boolean[] used = new boolean[MAX_SLOTS]; for (String node : places.keySet()) occupy(used, places.get(node), span(node), columns);
        int size = span(id), index = 0, after = anchor;
        for (String app : folder.members()) {
            int slot;
            if (index < size * size) slot = anchor + index / size * columns + index % size;
            else { slot = Math.min(MAX_SLOTS - 1, after); while (slot < MAX_SLOTS && used[slot]) slot++; if (slot == MAX_SLOTS) slot = vacancy(used, anchor, 1, columns, rows); }
            places.put(app, slot); used[slot] = true; after = slot + 1; index++;
        }
        return new AppWorkspaceLayout(places, groups, columns, rows);
    }
    AppWorkspaceLayout rename(String id, String name) { Folder folder = folder(id); if (folder == null) return this; Map<String, Folder> groups = new LinkedHashMap<>(folders); groups.put(id, new Folder(name, folder.members(), folder.color())); return new AppWorkspaceLayout(positions, groups, columns, rows); }
    AppWorkspaceLayout color(String id, int color) { Folder folder = folder(id); if (folder == null) return this; Map<String, Folder> groups = new LinkedHashMap<>(folders); groups.put(id, new Folder(folder.name(), folder.members(), color)); return new AppWorkspaceLayout(positions, groups, columns, rows); }
    AppWorkspaceLayout reorder(String id, List<String> members) { Folder folder = folder(id); if (folder == null || !new HashSet<>(folder.members()).equals(new HashSet<>(members))) throw new IllegalArgumentException("文件夹成员发生变化"); Map<String, Folder> groups = new LinkedHashMap<>(folders); groups.put(id, new Folder(folder.name(), members, folder.color())); return new AppWorkspaceLayout(positions, groups, columns, rows); }
    AppWorkspaceLayout create(List<String> members, String name, int anchor) {
        if (members.size() < 2 || members.size() > 9 || new HashSet<>(members).size() != members.size() || !apps().containsAll(members) || columns < 2 || rows < 2) throw new IllegalArgumentException("请选择2–9个应用，并确保桌面能放下2×2文件夹");
        AppWorkspaceLayout next = this; Map<String, Integer> places; Map<String, Folder> groups;
        for (String app : members) if (next.parent(app) != null) next = next.extract(app, anchor);
        places = new LinkedHashMap<>(next.positions); groups = new LinkedHashMap<>(next.folders); for (String app : members) places.remove(app);
        String id = "folder:" + java.util.UUID.randomUUID(); places.put(id, Math.max(0, Math.min(MAX_SLOTS - 1, anchor))); groups.put(id, new Folder(name, members)); return arrange(places, groups, columns, rows, id, false);
    }
    AppWorkspaceLayout swapPages(int first, int second) {
        int capacity = columns * rows; if (first < 0 || second < 0 || first >= pages(capacity) || second >= pages(capacity)) throw new IllegalArgumentException("页码无效");
        Map<String, Integer> places = new LinkedHashMap<>(); for (String id : ordered()) { int slot = slot(id), page = slot / capacity; places.put(id, (page == first ? second : page == second ? first : page) * capacity + slot % capacity); }
        return new AppWorkspaceLayout(places, folders, columns, rows);
    }
    AppWorkspaceLayout removeEmptyPage(int page) {
        int capacity = columns * rows; if (page < 0 || page >= pages(capacity)) throw new IllegalArgumentException("页码无效");
        for (String id : positions.keySet()) if (slot(id) / capacity == page) throw new IllegalArgumentException("只能删除空页，请先移动其中的内容");
        Map<String, Integer> places = new LinkedHashMap<>(); for (String id : ordered()) places.put(id, slot(id) >= (page + 1) * capacity ? slot(id) - capacity : slot(id)); return new AppWorkspaceLayout(places, folders, columns, rows);
    }
    @Override public boolean equals(Object other) { return other instanceof AppWorkspaceLayout layout && positions.equals(layout.positions) && folders.equals(layout.folders) && (folders.isEmpty() || columns == layout.columns && rows == layout.rows); }
    @Override public int hashCode() { return java.util.Objects.hash(positions, folders); }
}
