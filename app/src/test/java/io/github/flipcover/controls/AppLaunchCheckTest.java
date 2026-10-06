package io.github.flipcover.controls;

import android.view.Display;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppLaunchCheckTest {
    @Test public void explicitAppStartsLeaveLockAndPowerPolicyToAndroid() {
        assertTrue(new AppLauncher.Check(2, 2, Display.STATE_ON, true).launchable());
        assertTrue(new AppLauncher.Check(2, 2, Display.STATE_OFF, false).launchable());
        assertTrue(new AppLauncher.Check(2, 2, Display.STATE_UNKNOWN, true).launchable());
        assertFalse(new AppLauncher.Check(0, 0, Display.STATE_ON, false).launchable());
        assertFalse(new AppLauncher.Check(2, -1, Display.STATE_ON, false).launchable());
        assertFalse(new AppLauncher.Check(2, 3, Display.STATE_ON, false).launchable());
    }
    @Test public void readyRequiresTheSelectedLitSecondaryDisplayAndNoKeyguard() {
        assertTrue(new AppLauncher.Check(2, 2, Display.STATE_ON, false).ready());
        assertFalse(new AppLauncher.Check(0, 0, Display.STATE_ON, false).ready());
        assertFalse(new AppLauncher.Check(2, -1, Display.STATE_UNKNOWN, false).ready());
        assertFalse(new AppLauncher.Check(2, 3, Display.STATE_ON, false).ready());
        assertFalse(new AppLauncher.Check(2, 2, Display.STATE_OFF, false).ready());
        assertFalse(new AppLauncher.Check(2, 2, Display.STATE_ON, true).ready());
    }
    @Test public void screenFailuresAreNotMisreportedAsLockFailures() {
        for (AppLauncher.Check state : new AppLauncher.Check[]{new AppLauncher.Check(0, 2, Display.STATE_ON, true), new AppLauncher.Check(2, -1, Display.STATE_UNKNOWN, true), new AppLauncher.Check(2, 3, Display.STATE_ON, true), new AppLauncher.Check(2, 2, Display.STATE_OFF, true)}) {
            assertFalse(state.reason().equals("keyguard_showing"));
            assertFalse(AppLauncher.failureMessage(state.reason()).contains("锁屏"));
            assertFalse(AppLauncher.failureMessage(state.reason()).contains("解锁"));
        }
        assertEquals("keyguard_showing", new AppLauncher.Check(2, 2, Display.STATE_ON, true).reason());
        assertFalse(AppLauncher.failureMessage("owner_inactive").contains("解锁"));
        assertFalse(AppLauncher.failureMessage("invalid_component").contains("解锁"));
    }
}
