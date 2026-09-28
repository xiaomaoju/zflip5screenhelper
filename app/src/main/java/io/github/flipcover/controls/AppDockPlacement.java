package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.List;

/** Fixed Dock references: insertion and reorder never evict a different application. */
final class AppDockPlacement {
    static final int LIMIT = 4;
    static List<String> insert(List<String> pins, String id, int index) {
        List<String> next = new ArrayList<>(pins); next.remove(id);
        if (next.size() >= LIMIT) throw new IllegalArgumentException("底部常用已满" + LIMIT + "个，请先移出一个");
        if (index < 0 || index > next.size()) throw new IllegalArgumentException("无效的 Dock 落点");
        next.add(index, id); return List.copyOf(next);
    }
    static AppWorkspaceLayout workspace(AppWorkspaceLayout base, List<String> previous, List<String> pins, boolean compact) {
        List<String> available = new ArrayList<>(base.apps());
        for (String id : previous) if (!available.contains(id)) available.add(id);
        available.removeAll(pins); return base.reconcile(available, compact);
    }
}
