package com.kyant.backdrop.catalog.components;

import org.junit.Test;
import static org.junit.Assert.*;

public class LiquidTensionMotionTest {
    private LiquidTensionMotion motion(int count) { LiquidTensionMotion motion = new LiquidTensionMotion(); motion.configure(count, 36, 10, 3.6f, 2.88f, 3.5f); return motion; }
    private void settle(LiquidTensionMotion motion, float dt) { int frames = 0; while (motion.moving() && frames++ < 300) motion.advance(dt); assertFalse("no permanent frame loop", motion.moving()); }
    @Test public void firstButtonTravelsRightBeforeSeveringAndSecondWaits() {
        LiquidTensionMotion motion = motion(2); motion.drive(6, 60); settle(motion, 1f / 60);
        assertTrue("early adhesion follows slightly left", motion.position(0) < -10);
        float adhered = motion.position(0); motion.drive(24, 60); settle(motion, 1f / 60);
        assertTrue("button moves right during neck stretch", motion.position(0) > adhered + 5);
        assertFalse(motion.detached(0)); assertEquals(-10, motion.position(1), 0);
        motion.drive(36, 600); boolean bounced = false;
        for (int frame = 0; frame < 120; frame++) { boolean wasDetached = motion.detached(0); motion.advance(1f / 60); if (!wasDetached) assertEquals("successor waits through first severing frame", -10, motion.position(1), 0); bounced |= motion.position(0) > .5f; assertTrue(motion.position(0) <= 3.5f); }
        assertTrue(motion.detached(0)); assertTrue("severing leaves a visible outward impulse", bounced); assertFalse(motion.detached(1));
    }
    @Test public void fastPullStillUnlocksOneNodePerFrame() {
        LiquidTensionMotion motion = motion(2); motion.drive(120, 1800); motion.advance(1f / 30);
        assertTrue(motion.detached(0)); assertFalse(motion.detached(1)); assertEquals(-10, motion.position(1), 0);
        motion.advance(1f / 30); assertTrue(motion.detached(1)); settle(motion, 1f / 30); assertTrue(motion.ready());
        assertEquals(0, motion.position(0), 0); assertEquals(0, motion.position(1), 0);
    }
    @Test public void reverseAndCancellationCannotRetainOldImpulse() {
        LiquidTensionMotion motion = motion(2); motion.drive(72, 1000); settle(motion, 1f / 60);
        assertTrue(motion.ready()); motion.drive(24, 0); assertFalse(motion.ready()); settle(motion, 1f / 60);
        assertFalse(motion.detached(0)); assertFalse(motion.detached(1)); assertEquals(-10, motion.position(1), 0);
        motion.snap(0); assertFalse(motion.moving()); assertEquals(-10, motion.position(0), 0); assertEquals(0, motion.velocity(0), 0);
        motion.snap(72); assertFalse(motion.moving()); assertTrue(motion.ready());
    }
    @Test public void singleButtonAndAllFrameRatesConvergeExactly() {
        for (float dt : new float[]{1f / 30, 1f / 60, 1f / 120, .5f}) {
            LiquidTensionMotion motion = motion(1); motion.drive(36, 1800); settle(motion, dt);
            assertTrue(motion.ready()); assertEquals(0, motion.position(0), 0); assertEquals(0, motion.velocity(0), 0);
        }
    }
    @Test public void heldSplitDoesNotReconnectFromItsOwnRebound() {
        LiquidTensionMotion motion = motion(1); motion.drive(35.3f, 1600);
        for (int frame = 0; frame < 180; frame++) { motion.advance(1f / 120); if (motion.detached(0)) assertTrue(35.3f - 36 + 3.6f + motion.position(0) > 2.88f); }
        assertTrue(motion.detached(0)); assertFalse(motion.moving());
    }
    @Test public void geometryChangeDropsMomentumAndReusesSameState() {
        LiquidTensionMotion motion = motion(2); motion.drive(72, 1800); motion.advance(.016f); motion.configure(1, 36, 10, 3.6f, 2.88f, 3.5f);
        assertEquals(1, motion.getCount()); assertEquals(-10, motion.position(0), 0); assertEquals(0, motion.velocity(0), 0); assertFalse(motion.moving());
    }
    @Test public void budsGrowInReleaseOrderAndReturnToFullCircles() {
        LiquidTensionMotion motion = motion(2); motion.drive(24, 60); settle(motion, 1f / 60);
        assertTrue(motion.emergence(0) > 0 && motion.emergence(0) < 1); assertEquals(0, motion.emergence(1), 0);
        motion.drive(72, 1000); settle(motion, 1f / 60);
        assertEquals(1, motion.emergence(0), 0); assertEquals(1, motion.emergence(1), 0);
        motion.snap(0); assertEquals(0, motion.emergence(0), 0); assertEquals(0, motion.emergence(1), 0);
    }
    @Test public void scaleLerpIsBoundedAndOrderedWithTheRelease() {
        assertEquals(.2f, LiquidTensionMotion.circleScale(0), 0); assertEquals(1, LiquidTensionMotion.circleScale(1), 0);
        float previous = .2f; for (int frame = 0; frame <= 60; frame++) { float scale = LiquidTensionMotion.circleScale(frame / 60f); assertTrue(scale >= previous && scale <= 1); previous = scale; }
        LiquidTensionMotion motion = motion(2); motion.drive(24, 60); settle(motion, 1f / 60);
        assertTrue(motion.scale(0) > .2f && motion.scale(0) < 1); assertEquals(.2f, motion.scale(1), 0);
        motion.drive(72, 1000); settle(motion, 1f / 60); assertEquals(1, motion.scale(0), 0); assertEquals(1, motion.scale(1), 0);
    }
}
