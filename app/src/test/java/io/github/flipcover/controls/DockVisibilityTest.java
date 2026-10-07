package io.github.flipcover.controls;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class DockVisibilityTest {
    private final Set<String> desktop = Set.of("systemui", "launcher");
    @Test public void clockArrowUsesSavedRulesInsteadOfAnEarlierManualOrKeyboardOverride() {
        DockVisibility state = new DockVisibility(); state.foreground("systemui");
        assertTrue(state.automaticallyCompact(true, desktop));
        state.toggle(true, desktop); assertFalse(state.compact(true, desktop));
        assertTrue(state.automaticallyCompact(true, desktop));
        state.keyboard(true);
        assertFalse(state.automaticallyCompact(false, desktop));
        assertFalse(state.automaticallyCompact(true, Set.of()));
        assertFalse(state.automaticallyCompact(true, Set.of("launcher")));
        state.foreground("launcher"); assertTrue(state.automaticallyCompact(true, Set.of("launcher")));
    }
    @Test public void clockDetectionUsesOnlyActiveSystemUiApplicationMetadata() {
        assertTrue(DockVisibility.clockPage("com.android.systemui", "系统界面", "系统界面", true));
        assertTrue(DockVisibility.clockPage(null, "System UI", "System UI", true));
        assertFalse(DockVisibility.clockPage("com.android.systemui", "SubLauncherWindow", "系统界面", true));
        assertFalse(DockVisibility.clockPage("com.android.systemui", "系统界面", "系统界面", false));
        assertFalse(DockVisibility.clockPage("example.app", "系统界面", "系统界面", true));
        assertFalse(DockVisibility.clockPage(null, "应用", "系统界面", true));
        assertFalse(DockVisibility.clockPage(null, null, "系统界面", true));
    }
    @Test public void enteringClockDiscardsTemporaryShowBeforeTheNextDraw() {
        DockVisibility state = new DockVisibility(); Set<String> rules = Set.of("com.android.systemui");
        state.foreground("com.android.systemui"); state.toggle(true, rules); assertFalse(state.compact(true, rules));
        state.clearManual(); assertTrue(state.compact(true, rules));
        assertFalse(state.compact(false, rules)); assertFalse(state.compact(true, Set.of()));
    }
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
