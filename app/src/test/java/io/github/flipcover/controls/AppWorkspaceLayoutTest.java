package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppWorkspaceLayoutTest {
    @Test public void sparseMoveKeepsTheSourceHoleAndDoesNotMutateCommittedState() {
        AppWorkspaceLayout original = AppWorkspaceLayout.sequential(List.of("a", "b", "c"));
        AppWorkspaceLayout preview = original.move("b", 14, false);
        assertEquals(1, original.slot("b")); assertEquals(14, preview.slot("b"));
        assertEquals(0, preview.slot("a")); assertEquals(2, preview.slot("c")); assertEquals(2, preview.pages(10));
    }
    @Test public void occupiedDropShiftsOnlyThePathToNearestVacancy() {
        AppWorkspaceLayout original = new AppWorkspaceLayout(Map.of("a", 0, "b", 1, "c", 2, "d", 3, "far", 20));
        AppWorkspaceLayout moved = original.move("a", 2, false);
        assertEquals(2, moved.slot("a")); assertEquals(3, moved.slot("c")); assertEquals(4, moved.slot("d"));
        assertEquals(1, moved.slot("b")); assertEquals(20, moved.slot("far"));
    }
    @Test public void fullPageCanInsertAcrossTheBoundaryAndRetainsEveryApplication() {
        List<String> ids = new ArrayList<>(); for (int i = 0; i < 31; i++) ids.add("app" + i);
        AppWorkspaceLayout moved = AppWorkspaceLayout.sequential(ids).move("app0", 29, false);
        assertEquals(29, moved.slot("app0")); assertEquals(30, moved.slot("app29")); assertEquals(31, moved.slot("app30"));
        assertEquals(31, moved.size()); assertEquals(new HashSet<>(ids), moved.positions().keySet());
    }
    @Test public void compactMovesAreInsertionAndAlwaysRemoveHoles() {
        AppWorkspaceLayout original = new AppWorkspaceLayout(Map.of("a", 0, "b", 9, "c", 20));
        AppWorkspaceLayout moved = original.move("a", 999, true);
        assertEquals(List.of("b", "c", "a"), moved.ordered()); assertEquals(3, moved.extent());
        assertEquals(List.of("c", "b", "a"), moved.move("c", 0, true).ordered());
    }
    @Test public void completeCatalogReconciliationPreservesUserGapsAndAppendsNewApps() {
        AppWorkspaceLayout original = new AppWorkspaceLayout(Map.of("a", 4, "b", 12, "removed", 13));
        AppWorkspaceLayout next = original.reconcile(List.of("b", "a", "new"), false);
        assertEquals(4, next.slot("a")); assertEquals(12, next.slot("b")); assertEquals(13, next.slot("new")); assertEquals(-1, next.slot("removed"));
        assertEquals(next, next.reconcile(List.of("new", "a", "b"), false));
    }
    @Test public void resizingChangesPageProjectionWithoutWritingPositions() {
        AppWorkspaceLayout layout = new AppWorkspaceLayout(Map.of("a", 8, "b", 29));
        assertEquals(2, layout.pages(15)); assertEquals(3, layout.pages(10)); assertEquals(2, layout.pages(15));
        assertEquals(Map.of("a", 8, "b", 29), layout.positions());
    }
    @Test public void malformedSnapshotsAndImpossibleTargetsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> new AppWorkspaceLayout(Map.of("a", 1, "b", 1)));
        assertThrows(IllegalArgumentException.class, () -> new AppWorkspaceLayout(Map.of("a", -1)));
        assertThrows(IllegalArgumentException.class, () -> new AppWorkspaceLayout(Map.of("a", AppWorkspaceLayout.MAX_SLOTS)));
        AppWorkspaceLayout layout = new AppWorkspaceLayout(Map.of("a", 0));
        assertSame(layout, layout.move("missing", 1, false)); assertSame(layout, layout.move("a", -1, false)); assertSame(layout, layout.move("a", 4096, false));
    }
    @Test public void repeatedCrossPageMovesNeverDuplicateOrLoseAnApplication() {
        List<String> ids = new ArrayList<>(); for (int i = 0; i < 80; i++) ids.add("app" + i);
        AppWorkspaceLayout layout = AppWorkspaceLayout.sequential(ids); Random random = new Random(28);
        for (int i = 0; i < 1500; i++) {
            layout = layout.move(ids.get(random.nextInt(ids.size())), random.nextInt(200), i % 3 == 0);
            assertEquals(new HashSet<>(ids), layout.positions().keySet()); assertEquals(ids.size(), new HashSet<>(layout.positions().values()).size());
        }
    }
}
