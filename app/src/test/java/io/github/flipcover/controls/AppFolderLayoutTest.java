package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppFolderLayoutTest {
    private AppWorkspaceLayout apps(int count) { List<String> ids = new ArrayList<>(); for (int i = 0; i < count; i++) ids.add("app" + i); return AppWorkspaceLayout.sequential(ids).project(5, 3); }
    private void occupancy(AppWorkspaceLayout layout) {
        HashSet<Integer> used = new HashSet<>(); int capacity = layout.columns() * layout.rows();
        for (String id : layout.ordered()) { int slot = layout.slot(id), span = layout.span(id); assertTrue(slot % layout.columns() + span <= layout.columns()); assertTrue(slot % capacity / layout.columns() + span <= layout.rows()); for (int y = 0; y < span; y++) for (int x = 0; x < span; x++) assertTrue(used.add(slot + y * layout.columns() + x)); }
    }
    @Test public void mergingIntoBottomRightOfFullPageReflowsWithoutOverlapsOrLoss() {
        AppWorkspaceLayout original = apps(45), next = original.merge("app0", "app14"); String id = next.parent("app0");
        assertNotNull(id); assertEquals(List.of("app14", "app0"), next.folder(id).members()); assertEquals(2, next.span(id)); assertEquals(8, next.slot(id)); assertEquals(original.apps(), next.apps()); assertEquals(0, original.folders().size()); occupancy(next);
    }
    @Test public void capacityNineAcceptsEighthAndNinthAndRejectsTenthAtomically() {
        AppWorkspaceLayout next = apps(12).merge("app0", "app1"); String id = next.parent("app0");
        for (int i = 2; i < 9; i++) next = next.merge("app" + i, id);
        assertEquals(9, next.folder(id).members().size()); AppWorkspaceLayout full = next;
        assertThrows(IllegalArgumentException.class, () -> full.merge("app9", id)); assertEquals(9, full.folder(id).members().size()); assertTrue(full.slot("app9") >= 0); occupancy(full);
    }
    @Test public void crossFolderTransferRestoresSingleRemainingSourceApp() {
        AppWorkspaceLayout next = apps(8).merge("app0", "app1").merge("app2", "app3"); String target = next.parent("app2"), source = next.parent("app0"); int slot = next.slot(source);
        next = next.merge("app0", target); assertNull(next.folder(source)); assertEquals(slot, next.slot("app1")); assertEquals(3, next.folder(target).members().size()); assertEquals(8, next.apps().size()); occupancy(next);
    }
    @Test public void failedTransferLeavesBothFoldersUntouched() {
        AppWorkspaceLayout next = apps(12).create(List.of("app0", "app1", "app2", "app3", "app4", "app5", "app6", "app7", "app8"), "九个", 0).merge("app9", "app10");
        AppWorkspaceLayout before = next; String target = next.parent("app0"), source = next.parent("app9");
        assertThrows(IllegalArgumentException.class, () -> before.merge("app9", target)); assertEquals(2, before.folder(source).members().size()); assertEquals(12, before.apps().size());
    }
    @Test public void nineMemberDissolveAndExtractPreserveAllApps() {
        AppWorkspaceLayout original = apps(80), next = original.create(original.ordered().subList(0, 9), "九个", 14); String id = next.parent("app0");
        next = next.extract("app0", 42); assertEquals(8, next.folder(id).members().size()); assertEquals(42, next.slot("app0")); int anchor = next.slot(id); List<String> order = next.folder(id).members(); next = next.dissolve(id);
        assertEquals(anchor, next.slot(order.get(0))); assertEquals(anchor + 1, next.slot(order.get(1))); assertEquals(anchor + next.columns(), next.slot(order.get(2))); assertEquals(anchor + next.columns() + 1, next.slot(order.get(3)));
        assertEquals(original.apps(), next.apps()); assertEquals(0, next.folders().size()); occupancy(next);
    }
    @Test public void tinyProjectionPreservesCanonicalFolderAndCanRestore() {
        AppWorkspaceLayout saved = apps(30).merge("app0", "app1"), narrow = saved.project(3, 1); String id = saved.parent("app0");
        assertEquals(1, narrow.span(id)); assertEquals(2, saved.span(id)); assertEquals(saved.apps(), narrow.apps()); occupancy(narrow); assertSame(saved, saved.project(5, 3));
        assertThrows(IllegalArgumentException.class, () -> narrow.merge("app2", "app3")); occupancy(saved.project(3, 2));
    }
    @Test public void importRejectsDuplicateOwnershipNestingAndOverlappingFootprints() {
        AppWorkspaceLayout.Folder group = new AppWorkspaceLayout.Folder("组", List.of("a", "b"));
        assertThrows(IllegalArgumentException.class, () -> new AppWorkspaceLayout(Map.of("f", 0, "a", 4), Map.of("f", group), 5, 3));
        assertThrows(IllegalArgumentException.class, () -> new AppWorkspaceLayout(Map.of("f", 0, "c", 5), Map.of("f", group), 5, 3));
        assertThrows(IllegalArgumentException.class, () -> new AppWorkspaceLayout(Map.of("f", 4), Map.of("f", group), 5, 3));
        assertThrows(IllegalArgumentException.class, () -> new AppWorkspaceLayout(Map.of("f", 0, "g", 3), Map.of("f", group, "g", new AppWorkspaceLayout.Folder("组", List.of("f", "c"))), 5, 3));
    }
    @Test public void fullCatalogRemovalNormalizesFoldersWithoutRevivingOrDuplicatingMembers() {
        AppWorkspaceLayout next = apps(4).merge("app0", "app1"); String id = next.parent("app0");
        next = next.reconcile(List.of("app0", "app2", "app3", "new"), false); assertNull(next.folder(id)); assertEquals(4, next.apps().size()); assertTrue(next.slot("app0") >= 0); occupancy(next);
    }
    @Test public void compactWithFoldersIsIdempotentAndContainsNoOverlap() {
        AppWorkspaceLayout next = apps(40).merge("app0", "app20").move("app5", 200, false).compact(); assertEquals(next, next.compact()); occupancy(next); assertEquals(40, next.apps().size());
    }
    @Test public void reorderRenameAndColorKeepMembershipAndLayout() {
        AppWorkspaceLayout next = apps(5).merge("app0", "app1"); String id = next.parent("app0"); Map<String, Integer> positions = next.positions();
        next = next.reorder(id, List.of("app0", "app1")).rename(id, " 常用工具 ").color(id, 3); assertEquals("常用工具", next.folder(id).name()); assertEquals(3, next.folder(id).color()); assertEquals(positions, next.positions());
        AppWorkspaceLayout unchanged = next; assertThrows(IllegalArgumentException.class, () -> unchanged.reorder(id, List.of("app0", "app2"))); assertThrows(IllegalArgumentException.class, () -> unchanged.rename(id, " "));
    }
    @Test public void pageSwapAndEmptyRemovalAreReversibleAndProtectOccupiedPages() {
        AppWorkspaceLayout original = apps(20).merge("app0", "app1"), swapped = original.swapPages(0, 1); assertEquals(original, swapped.swapPages(0, 1)); occupancy(swapped);
        assertThrows(IllegalArgumentException.class, () -> original.removeEmptyPage(0));
        AppWorkspaceLayout sparse = new AppWorkspaceLayout(Map.of("a", 0, "b", 31)); assertEquals(16, sparse.removeEmptyPage(1).slot("b"));
    }
    @Test public void batchCreationMovesMembersOutOfOtherFoldersOnce() {
        AppWorkspaceLayout original = apps(12).merge("app0", "app1").merge("app2", "app3");
        AppWorkspaceLayout next = original.create(List.of("app0", "app2", "app5"), "收集", 10); assertEquals(1, next.folders().size()); assertTrue(next.slot("app1") >= 0); assertTrue(next.slot("app3") >= 0); assertEquals(original.apps(), next.apps()); occupancy(next);
    }
    @Test public void randomizedFolderTransactionsAndRotationsConserveEveryApp() {
        AppWorkspaceLayout layout = apps(80); java.util.Set<String> expected = layout.apps(); Random random = new Random(91028);
        for (int i = 0; i < 600; i++) {
            String app = "app" + random.nextInt(80); List<String> nodes = layout.ordered(); String target = nodes.get(random.nextInt(nodes.size()));
            try { switch (i % 6) { case 0 -> layout = layout.merge(app, target); case 1 -> layout = layout.parent(app) == null ? layout.move(app, random.nextInt(250), false) : layout.extract(app, random.nextInt(250)); case 2 -> layout = layout.dissolve(target); case 3 -> layout = layout.project(3 + random.nextInt(3), 2 + random.nextInt(3)); case 4 -> layout = layout.compact(); default -> layout = layout.reconcile(new ArrayList<>(expected), false); } } catch (IllegalArgumentException capacityOrSameOwner) { }
            assertEquals(expected, layout.apps()); occupancy(layout);
        }
    }
    @Test public void pinyinSearchSupportsFullSpellingInitialsAndLatinNames() { assertTrue(AppSearchIndex.romanize("微信").contains("weixin wx")); assertTrue(AppSearchIndex.romanize("支付宝").contains("zhifubao zfb")); assertTrue(AppSearchIndex.romanize("Calendar").contains("calendar")); }
}
