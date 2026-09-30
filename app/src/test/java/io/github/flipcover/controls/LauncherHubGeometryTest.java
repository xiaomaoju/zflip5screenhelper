package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class LauncherHubGeometryTest {
    @Test public void hostsShareEveryRegionExceptTheButtonPagingStrip() {
        for (float density : new float[]{1, 2, 2.125f, 2.75f}) for (boolean right : new boolean[]{false, true}) {
            var floating = AppLauncherStyle.hubGeometry(748, 620, density, right, false);
            var nativeCard = AppLauncherStyle.hubGeometry(748, 620, density, right, true);
            assertEquals(floating.bodyHeight(), nativeCard.bodyHeight()); assertEquals(floating.catalogLeft(), nativeCard.catalogLeft()); assertEquals(floating.catalogWidth(), nativeCard.catalogWidth());
            assertEquals(floating.railLeft(), nativeCard.railLeft()); assertEquals(floating.railListHeight(), nativeCard.railListHeight()); assertEquals(floating.gridWidth(), nativeCard.gridWidth()); assertEquals(floating.dockHeight(), nativeCard.dockHeight());
            assertEquals(nativeCard.pagerHeight() - floating.pagerHeight(), floating.gridHeight() - nativeCard.gridHeight());
            assertEquals(620, nativeCard.bodyHeight() + nativeCard.dockHeight());
        }
    }
    @Test public void narrowHostHasNoNegativeBodyOrGridSizes() {
        var small = AppLauncherStyle.hubGeometry(20, 20, 2, false, true);
        assertEquals(0, small.bodyHeight()); assertEquals(0, small.gridWidth()); assertEquals(0, small.gridHeight()); assertEquals(0, small.railListHeight());
    }
}
