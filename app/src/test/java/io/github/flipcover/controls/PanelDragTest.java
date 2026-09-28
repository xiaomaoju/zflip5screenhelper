package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class PanelDragTest {
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
