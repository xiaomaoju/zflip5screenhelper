package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplaysTest {
    @Test public void physicalCoverModePassesButSamsungAdjustedSizeWouldRejectIt() {
        assertTrue(Displays.coverSize(748, 720));
        assertTrue(Displays.coverSize(720, 748));
        assertFalse(Displays.coverSize(1496, 1440));
        assertFalse(Displays.coverSize(1440, 1496));
    }
    @Test public void identifyingPhysicalModesRetainsPhoneAndLargeScreenExclusion() {
        assertFalse(Displays.coverSize(1080, 1920));
        assertFalse(Displays.coverSize(1080, 2640));
        assertFalse(Displays.coverSize(1920, 1080));
        assertFalse(Displays.coverSize(2000, 2000));
        assertFalse(Displays.coverSize(0, 720));
    }
}
