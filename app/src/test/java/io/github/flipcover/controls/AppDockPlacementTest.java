package io.github.flipcover.controls;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class AppDockPlacementTest {
    @Test public void insertsAtTheChosenGapWithoutChangingInput() {
        List<String> pins = List.of("a", "b");
        assertEquals(List.of("c", "a", "b"), AppDockPlacement.insert(pins, "c", 0));
        assertEquals(List.of("a", "c", "b"), AppDockPlacement.insert(pins, "c", 1));
        assertEquals(List.of("a", "b", "c"), AppDockPlacement.insert(pins, "c", 2));
        assertEquals(List.of("a", "b"), pins);
        assertEquals(List.of("a"), AppDockPlacement.insert(List.of(), "a", 0));
    }
    @Test public void fullDockReordersExistingReferenceWithoutDuplicates() {
        List<String> pins = List.of("a", "b", "c", "d");
        assertEquals(List.of("d", "a", "b", "c"), AppDockPlacement.insert(pins, "d", 0));
        assertEquals(List.of("b", "c", "d", "a"), AppDockPlacement.insert(pins, "a", 3));
        assertEquals(pins, AppDockPlacement.insert(pins, "b", 1));
    }
    @Test public void fullDockNeverEvictsAnUnrelatedApp() {
        List<String> pins = List.of("a", "b", "c", "d");
        assertThrows(IllegalArgumentException.class, () -> AppDockPlacement.insert(pins, "e", 1));
        assertEquals(List.of("a", "b", "c", "d"), pins);
    }
    @Test public void existingThreePinsAcceptFourthAtEveryGap() {
        List<String> pins = List.of("a", "b", "c");
        for (int index = 0; index <= 3; index++) { List<String> next = AppDockPlacement.insert(pins, "d", index); assertEquals(4, next.size()); assertEquals("d", next.get(index)); List<String> old = new java.util.ArrayList<>(next); old.remove("d"); assertEquals(pins, old); }
    }
    @Test public void invalidDropCannotBecomeAnAppend() {
        assertThrows(IllegalArgumentException.class, () -> AppDockPlacement.insert(List.of("a"), "b", -1));
        assertThrows(IllegalArgumentException.class, () -> AppDockPlacement.insert(List.of("a"), "b", 2));
    }
    @Test public void pinMovesOwnershipOutOfFolderAndUnpinReturnsToDesktop() {
        AppWorkspaceLayout base = AppWorkspaceLayout.sequential(List.of("a", "b", "c")).create(List.of("a", "b"), "工具", 0);
        AppWorkspaceLayout pinned = AppDockPlacement.workspace(base, List.of(), List.of("a"), false);
        assertFalse(pinned.apps().contains("a")); assertTrue(pinned.folders().isEmpty()); assertEquals(2, pinned.apps().size());
        AppWorkspaceLayout returned = AppDockPlacement.workspace(pinned, List.of("a"), List.of(), false);
        assertEquals(base.apps(), returned.apps()); assertNull(returned.parent("a")); assertTrue(returned.slot("a") >= 0);
        assertNotNull(base.parent("a"));
    }
    @Test public void reorderingPinsDoesNotChangeDesktopAndPackingStaysValid() {
        AppWorkspaceLayout base = AppWorkspaceLayout.sequential(List.of("a", "b", "c", "d"));
        AppWorkspaceLayout pinned = AppDockPlacement.workspace(base, List.of(), List.of("a", "c"), true);
        assertEquals(pinned.compact(), pinned);
        assertEquals(pinned, AppDockPlacement.workspace(pinned, List.of("a", "c"), List.of("c", "a"), true));
    }
}
