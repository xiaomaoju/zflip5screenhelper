package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class PanelDragTest {
    @Test public void dockClosesBeyondFortyPercentOrWithDownwardInertia() {
        assertFalse(PanelDrag.shouldDismissDock(19, 50, 0, 100));
        assertFalse(PanelDrag.shouldDismissDock(20, 50, 0, 100));
        assertTrue(PanelDrag.shouldDismissDock(21, 50, 0, 100));
        assertTrue(PanelDrag.shouldDismissDock(5, 50, 100, 100));
        assertFalse(PanelDrag.shouldDismissDock(5, 50, -100, 100));
        assertFalse(PanelDrag.shouldDismissDock(0, 50, 500, 100));
    }
    @Test public void freshPanelTopTracksFingerFromCutoutHomeAndCalibratedEntries() {
        DockGeometry.Box[] cuts = {new DockGeometry.Box(351, 682, 369, 66), new DockGeometry.Box(0, 351, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(682, 0, 66, 369)};
        for (int rotation = 0; rotation < 4; rotation++) for (boolean measured : new boolean[]{true, false}) {
            int width = rotation % 2 == 0 ? 720 : 748, height = rotation % 2 == 0 ? 748 : 720;
            java.util.List<DockGeometry.Box> cutouts = measured ? java.util.List.of(cuts[rotation]) : java.util.List.of();
            DockGeometry.Placement dock = DockGeometry.resolve(width, height, cutouts, 2.125f, (rotation + 3) % 4, .46f, .088f, measured);
            DockGeometry.Placement entry = DockGeometry.panelEntry(dock, dock, width, height, cutouts, 2.125f, 24, rotation == 0 ? "bottom_right" : "top_left");
            for (float fraction : new float[]{.1f, .5f, .9f}) {
                float originY = entry.touch().y() + entry.touch().height() * fraction;
                float start = PanelDrag.entryStart(entry.edge(), originY, 0, height);
                for (float traveled : new float[]{20, 120, 240, 80, 0, -10}) {
                    float progress = PanelDrag.progress(start, traveled, height);
                    float leading = entry.edge() == DockGeometry.TOP ? progress * height : (1 - progress) * height;
                    float expected = originY + (entry.edge() == DockGeometry.TOP ? traveled : -traveled);
                    assertEquals(Math.max(0, Math.min(height, expected)), leading, .001f);
                }
                assertEquals(1, PanelDrag.progress(start, height, height), 0);
                assertFalse(PanelDrag.shouldOpen(0, height, 0, 2.125f));
                assertFalse(PanelDrag.shouldOpen(20, height, 0, 2.125f));
            }
        }
    }
    @Test public void entryAnchorUsesDisplayCoordinatesWithoutChangingFrameGeometry() {
        float start = PanelDrag.entryStart(600, 30, 720);
        assertEquals(480, 30 + (1 - PanelDrag.progress(start, 120, 720)) * 720, .001f);
    }
    @Test public void diagonalEntryAndSmallInitialJitterCanStillOpen() {
        PanelDrag drag = new PanelDrag(DockGeometry.BOTTOM, 8); drag.move(10, -2); assertEquals(28, drag.move(24, -28), 0); assertTrue(drag.active());
        assertTrue(PanelDrag.shouldOpen(82, 748, 0, 2)); assertFalse(PanelDrag.shouldOpen(15, 748, 2000, 2));
        assertFalse(PanelDrag.shouldOpen(150, 748, -1600, 2));
    }
    @Test public void followsInwardMotionOnAllFourEdges() {
        float[][] motion = {{0, -120}, {120, 0}, {0, 120}, {-120, 0}};
        for (int edge = 0; edge < 4; edge++) {
            PanelDrag drag = new PanelDrag(edge, 8);
            assertEquals(120, drag.move(motion[edge][0], motion[edge][1]), 0);
            assertTrue(drag.active());
            assertEquals(60, drag.move(motion[edge][0] / 2, motion[edge][1] / 2), 0);
        }
    }
    @Test public void wrongAxisAndOutwardMotionNeverBecomePanelDrags() {
        PanelDrag horizontal = new PanelDrag(DockGeometry.BOTTOM, 8);
        assertEquals(0, horizontal.move(30, -2), 0);
        assertEquals(0, horizontal.move(30, -180), 0);
        assertFalse(horizontal.active());
        PanelDrag outward = new PanelDrag(DockGeometry.BOTTOM, 8);
        outward.move(0, 20); outward.move(0, -200); assertFalse(outward.active());
    }
    @Test public void shortDragSpringsBackAndCommittedDragOrFlingOpens() {
        assertFalse(PanelDrag.shouldOpen(30, 500, 0, 2));
        assertTrue(PanelDrag.shouldOpen(30, 500, 3000, 2));
        assertTrue(PanelDrag.shouldOpen(200, 500, 0, 2));
        assertTrue(PanelDrag.shouldOpen(90, 500, 1800, 2));
        assertFalse(PanelDrag.shouldOpen(90, 500, -1800, 2));
    }
    @Test public void interruptedPanelContinuesFromItsVisiblePosition() {
        for (float start : new float[]{.1f, .5f, .9f}) {
            assertEquals(start, PanelDrag.progress(start, 0, 400), .0001f);
            float moved = PanelDrag.progress(start, 20, 400);
            assertEquals(start + .05f, moved, .0001f);
            assertEquals(start, PanelDrag.progress(moved, -20, 400), .0001f);
        }
        assertEquals(1, PanelDrag.progress(.9f, 200, 400), 0);
        assertEquals(0, PanelDrag.progress(.1f, -200, 400), 0);
    }
    @Test public void acceptedPullCanReversePastItsTouchOriginOnEveryEdge() {
        float[][] motion = {{0, -40}, {40, 0}, {0, 40}, {-40, 0}};
        for (int edge = 0; edge < 4; edge++) {
            PanelDrag drag = new PanelDrag(edge, 8);
            float initialDistance = drag.move(motion[edge][0], motion[edge][1]);
            assertEquals(.5f, PanelDrag.progress(.5f, initialDistance - initialDistance, 400), 0);
            float reversedDistance = drag.move(-motion[edge][0], -motion[edge][1]);
            assertTrue(drag.active()); assertEquals(-40, reversedDistance, 0);
            assertEquals(.3f, PanelDrag.progress(.5f, reversedDistance - initialDistance, 400), .0001f);
        }
    }
}
