package io.github.flipcover.controls;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class StatusAppVisibilityTest {
    @Test public void missingEventsDuringWindowReplacementCannotFlashOrExtendTheDeadline() {
        StatusAppVisibility state = new StatusAppVisibility(); state.applications(Set.of("com.example.qq"));
        state.observed("com.example.qq", 0); assertTrue(state.hidden());
        state.observed(null, 100); assertTrue(state.hidden());
        state.observed(null, 300); assertEquals(300, state.remaining(300));
        state.observed("com.example.qq", 450); assertTrue(state.hidden()); assertEquals(0, state.remaining(450));
        state.observed(null, 500); state.observed(null, 999); assertTrue(state.hidden());
        state.settle(1000); assertFalse(state.hidden());
    }
    @Test public void anotherAppAndRemovingTheLastRuleRestoreImmediately() {
        StatusAppVisibility state = new StatusAppVisibility(); state.applications(Set.of("com.example.qq"));
        state.observed("com.example.qq", 0); state.observed(null, 10);
        state.observed("com.example.home", 20); assertFalse(state.hidden()); assertEquals(0, state.remaining(20));
        state.observed("com.example.qq", 30); state.applications(Set.of());
        assertFalse(state.enabled()); assertFalse(state.hidden()); assertEquals(0, state.remaining(30));
        state.observed("com.example.qq", 40); state.applications(Set.of("com.example.qq"));
        assertFalse(state.hidden()); // Disabled observation cannot leak into a later session.
    }
    @Test public void unknownStartupAndDisplayResetCannotBorrowAnOldApp() {
        StatusAppVisibility state = new StatusAppVisibility(); state.applications(Set.of("com.example.qq"));
        state.observed(null, 0); assertFalse(state.hidden()); assertEquals(0, state.remaining(0));
        state.observed("com.example.qq", 10); state.reset(); assertFalse(state.hidden());
    }
    @Test public void emptyDockRulesDoNotHideUnknownAppsOrOverrideManualAndKeyboardChoices() {
        DockVisibility dock = new DockVisibility();
        assertFalse(dock.compact(true, Set.of())); assertFalse(dock.needsForeground());
        dock.toggle(true, Set.of()); assertTrue(dock.compact(true, Set.of())); assertTrue(dock.needsForeground());
        dock.keyboard(true); assertTrue(dock.compact(false, Set.of()));
        dock.toggle(false, Set.of()); assertFalse(dock.compact(false, Set.of()));
    }
}
