package io.github.flipcover.controls;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecentTasksTest {
    private RecentTasks.Task task(int id, String name, boolean visible) { return new RecentTasks.Task(id, 2, 0, name + "/.Main", name, visible); }
    @Test public void staleIdentityCannotClearAnotherDisplayUserOrApplication() {
        RecentTasks.Task expected = task(7, "a.example", false);
        assertTrue(RecentTasks.canClear(expected, expected));
        assertFalse(RecentTasks.canClear(expected, new RecentTasks.Task(7, 0, 0, "a.example/.Main", "a.example", false)));
        assertFalse(RecentTasks.canClear(expected, new RecentTasks.Task(7, 3, 0, "a.example/.Main", "a.example", false)));
        assertFalse(RecentTasks.canClear(expected, new RecentTasks.Task(7, 2, 10, "a.example/.Main", "a.example", false)));
        assertFalse(RecentTasks.canClear(expected, task(7, "b.example", false)));
        assertFalse(RecentTasks.canClear(expected, task(7, "a.example", true)));
    }
    @Test public void groupsDeduplicateAppsAndRetainSystemOrderWithoutFillingEmptySlots() {
        List<RecentTasks.Task> source = List.of(task(1, "pin.app", false), task(2, "b.app", false), task(3, "b.app", false), task(4, "c.app", true));
        assertEquals(List.of(source.get(1), source.get(3)), RecentTasks.apps(source, Set.of("pin.app")));
        assertTrue(RecentTasks.apps(List.of(), Set.of()).isEmpty());
    }
    @Test public void clearTargetsIncludeMatchingBackgroundTasksButNotPinsVisibleOrHiddenFifthApp() {
        List<RecentTasks.Task> source = List.of(task(1, "pin.app", false), task(2, "a.app", true), task(3, "a.app", false), task(4, "b.app", false), task(5, "c.app", false), task(6, "d.app", false), task(7, "e.app", false), task(8, "f.app", false));
        assertEquals(List.of(source.get(1), source.get(3), source.get(4), source.get(5)), RecentTasks.apps(source, Set.of("pin.app")));
        assertEquals(List.of(source.get(2), source.get(3), source.get(4), source.get(5)), RecentTasks.clearTargets(source, Set.of("pin.app")));
    }
    @Test public void coverWidthsUseFiveColumnsAndDockFitsWithoutStretchingSmallGroups() {
        assertEquals(5, RecentTasks.columns(270)); assertEquals(5, RecentTasks.columns(195)); assertEquals(3, RecentTasks.columns(140));
        assertEquals(34, RecentTasks.dockCell(338, 4, 34, 32));
        int compact = RecentTasks.dockCell(250, 8, 34, 32); assertTrue(compact * 8 + 32 <= 250);
    }
    @Test public void taskPageKeepsWindowsAndProtectsPinsAndForeground() {
        List<RecentTasks.Task> source = List.of(task(1, "pin.app", false), task(2, "a.app", true), task(3, "a.app", false), task(4, "a.app", false), task(5, "b.app", false), task(6, "c.app", false), task(7, "d.app", false), task(8, "e.app", false), task(9, "f.app", false));
        List<RecentTasks.Task> targets = RecentTasks.backgroundTargets(source, Set.of("pin.app"));
        assertEquals(source.subList(2, source.size()), targets);
        assertNotEquals(RecentTasks.key(source.get(2)), RecentTasks.key(source.get(3)));
        assertEquals(RecentTasks.key(source.get(1)), RecentTasks.key(task(2, "a.app", false)));
        assertThrows(UnsupportedOperationException.class, () -> targets.clear());
    }
    @Test public void locksSeparateWindowsAndProtectEveryClearSelection() {
        RecentTasks.Locks locks = new RecentTasks.Locks(); RecentTasks.Task first = task(1, "a.app", false), sibling = task(2, "a.app", false);
        assertTrue(locks.toggle(first)); assertTrue(locks.contains(first)); assertFalse(locks.contains(sibling));
        assertEquals(List.of(sibling), locks.unlocked(List.of(first, sibling)));
        assertTrue(locks.contains(task(1, "a.app", true))); assertTrue(locks.toggle(first)); assertFalse(locks.contains(first));
    }
    @Test public void incompleteTaskListsCannotForgetLocksOrCrossDisplayState() {
        RecentTasks.Locks locks = new RecentTasks.Locks(); RecentTasks.Task first = task(1, "a.app", false); locks.toggle(first);
        locks.reconcile(2, 0, List.of(), false); assertTrue(locks.contains(first));
        locks.reconcile(3, 0, List.of(), true); locks.reconcile(2, 10, List.of(), true); assertTrue(locks.contains(first));
        locks.reconcile(2, 0, List.of(task(1, "other.app", false)), false); assertFalse(locks.contains(first));
        locks.toggle(first); locks.reconcile(2, 0, List.of(), true); assertFalse(locks.contains(first));
    }
    @Test public void processLocksHaveABoundedLifetimeAndCapacity() {
        RecentTasks.Locks locks = new RecentTasks.Locks(); for (int id = 0; id < 32; id++) assertTrue(locks.toggle(task(id, "a.app", false)));
        assertFalse(locks.toggle(task(33, "a.app", false))); locks.clear(); assertTrue(locks.toggle(task(33, "a.app", false)));
    }
}
