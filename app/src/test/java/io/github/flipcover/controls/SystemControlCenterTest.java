package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class SystemControlCenterTest {
    @Test public void restoresSamsungGestureGateEvenWhenServerFlagsDidNotChange() throws Exception {
        int[] own = {1}, other = {0}, lastCombined = {1}; boolean[] blocked = {false};
        SystemControlCenter.FlagWriter server = flags -> {
            own[0] = flags;
            int combined = flags | other[0];
            if (combined != lastCombined[0]) { lastCombined[0] = combined; blocked[0] = (combined & 5) != 0; }
            assertTrue("Recovery must never release quick settings", (combined & 1) != 0);
        };
        server.write(1); assertFalse("Reproduce Samsung fold reset despite unchanged server policy", blocked[0]);
        SystemControlCenter.apply(false, server);
        assertTrue(blocked[0]); assertEquals("Do not retain the extra shade restriction", 1, own[0]);
        other[0] = 1;
        SystemControlCenter.apply(true, flags -> own[0] = flags);
        assertEquals(0, own[0]); assertEquals("Enabling preserves another owner's restriction", 1, own[0] | other[0]);
    }
    @Test public void failedPulseStillRestoresTheNarrowRestriction() {
        java.util.List<Integer> writes = new java.util.ArrayList<>();
        try {
            SystemControlCenter.apply(false, flags -> { writes.add(flags); if (flags == 5) throw new IllegalStateException("Rejected pulse"); });
            fail("Failure must remain visible");
        } catch (Exception expected) { assertEquals(java.util.List.of(5, 1), writes); }
    }
    @Test public void sharedFlagsIgnoreStaleCoverAndDexCopies() {
        assertEquals(1, SystemControlCenter.state("  displayId=0\n    mDisabled1=0x0\n    mDisabled2=0x0\n  displayId=1\n    mDisabled1=0x1600000\n    mDisabled2=0x1\n  DexdisplayId=0\n    mDexDisabled2=0x1"));
        assertEquals(0, SystemControlCenter.state("displayId=0\nmDisabled1=0x0\nmDisabled2=0x1\ndisplayId=1\nmDisabled1=0x0\nmDisabled2=0x0"));
    }
    @Test public void allExpansionRestrictionsCountButNavigationFlagsDoNot() {
        assertEquals(0, SystemControlCenter.state("displayId=0\nmDisabled1=0x10000\nmDisabled2=0x0"));
        assertEquals(0, SystemControlCenter.state("displayId=0\nmDisabled1=0x0\nmDisabled2=0x4"));
        assertEquals(1, SystemControlCenter.state("displayId=0\nmDisabled1=0x01600000\nmDisabled2=0x2"));
    }
    @Test public void malformedIncompleteOrUnsupportedDumpsStayUnknown() {
        for (String dump : new String[]{null, "", "Permission Denial", "displayId=1\nmDisabled1=0x0\nmDisabled2=0x0", "displayId=0\nmDisabled1=0x0", "displayId=0\nmDisabled1=0x0\ndisplayId=1\nmDisabled2=0x0", "displayId=0\nmDisabled1=oops\nmDisabled2=0x0", "displayId=0\nmDisabled1=0x100000000\nmDisabled2=0x0", "displayId=0\nmDisabled1=0x0\nmDisabled1=0x1\nmDisabled2=0x0"}) assertEquals(-1, SystemControlCenter.state(dump));
    }
}
