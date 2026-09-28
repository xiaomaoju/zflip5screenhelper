package io.github.flipcover.controls;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class DockVisibilityTest {
    private final Set<String> desktop = Set.of("systemui", "launcher");
    @Test public void keyboardTemporarilyCompactsAndRestoresManualState() {
        DockVisibility state = new DockVisibility(); state.foreground("launcher"); state.toggle(true, desktop);
        state.keyboard(true); assertTrue(state.compact(true, desktop));
        state.toggle(true, desktop); assertFalse(state.compact(true, desktop));
        state.keyboard(true); assertFalse(state.compact(true, desktop));
        state.keyboard(false); assertFalse(state.compact(true, desktop));
        state.keyboard(true); assertTrue(state.compact(true, desktop));
        state.foreground("browser"); state.keyboard(false); assertFalse(state.compact(true, desktop));
    }
    @Test public void keyboardAvoidanceIsIndependentOfDesktopRules() {
        DockVisibility state = new DockVisibility(); state.foreground("browser"); state.keyboard(true);
        assertTrue(state.compact(false, desktop)); state.keyboard(false); assertFalse(state.compact(false, desktop));
        state.keyboard(true); state.reset(); assertFalse(state.compact(false, desktop));
    }
    @Test public void desktopAndApplicationsHaveDifferentDefaults() {
        DockVisibility state = new DockVisibility();
        assertTrue(state.compact(true, desktop));
        state.foreground("launcher"); assertTrue(state.compact(true, desktop));
        state.foreground("browser"); assertFalse(state.compact(true, desktop));
        state.foreground("systemui"); assertTrue(state.compact(true, desktop));
    }
    @Test public void manualChoiceSurvivesSameAppPagesAndUnknownEvents() {
        DockVisibility state = new DockVisibility(); state.foreground("launcher"); state.toggle(true, desktop);
        assertFalse(state.compact(true, desktop));
        state.foreground("launcher"); state.foreground(null); state.foreground(""); assertFalse(state.compact(true, desktop));
        state.foreground("browser"); state.toggle(true, desktop); assertTrue(state.compact(true, desktop));
        state.foreground("launcher"); state.foreground("browser"); assertFalse(state.compact(true, desktop));
    }
    @Test public void manualControlRemainsWhenAutomaticIsDisabled() {
        DockVisibility state = new DockVisibility(); state.foreground("launcher"); assertFalse(state.compact(false, desktop));
        state.toggle(false, desktop); assertTrue(state.compact(false, desktop));
        state.reset(); assertFalse(state.compact(false, desktop));
    }
}
