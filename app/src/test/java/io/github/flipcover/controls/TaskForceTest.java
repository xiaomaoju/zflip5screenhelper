package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class TaskForceTest {
    private TaskForce graph() { TaskForce force = new TaskForce(); force.bounds(19 * 245, 245); force.count = 3; force.order[0] = 0; force.order[1] = 1; force.order[2] = 2; return force; }
    private void rest(TaskForce force, float dt) { boolean moving = true; int frames = 0; while (moving && frames++ < 1200) moving = force.advance(dt, false, true, 1); assertFalse("solver stops", moving); assertEquals(force.nearest(), force.offset, 0); assertFalse(force.snapping); for (int i = 0; i < 6; i++) { assertEquals(0, force.x[i], 0); assertEquals(0, force.vx[i], 0); } }
    @Test public void dragFlingAndInterruptedCaptureAlwaysEndAtCenter() {
        java.util.Random random = new java.util.Random(90817);
        for (int i = 0; i < 200; i++) {
            TaskForce force = graph(); force.offset = random.nextFloat() * force.maximum; force.release((random.nextFloat() - .5f) * 9000);
            float dt = i % 3 == 0 ? 1f / 120 : i % 3 == 1 ? 1f / 60 : 1f / 30;
            if (i % 4 == 0) { for (int frame = 0; frame < 30; frame++) force.advance(dt, false, true, 1); float paused = force.offset; force.interrupt(); for (int frame = 0; frame < 30; frame++) force.advance(dt, true, true, 1); assertEquals(paused, force.offset, 0); force.release(0); }
            rest(force, dt);
        }
    }
    @Test public void captureTransmitsLoadThroughNeighborsClearAndOppositeBackground() {
        TaskForce force = graph(); force.offset = 8 * 245 + 80; force.release(0); float card = 0, neighbor = 0, clear = 0, background = 0; boolean opposite = false;
        for (int frame = 0; frame < 160; frame++) {
            force.advance(1f / 60, false, true, 1); card = Math.max(card, Math.abs(force.x[1])); neighbor = Math.max(neighbor, Math.abs(force.x[0])); clear = Math.max(clear, Math.abs(force.x[4])); background = Math.max(background, Math.abs(force.x[5])); opposite |= force.x[1] * force.x[5] < -.1f;
        }
        assertTrue(card > 5); assertTrue(neighbor > .5f); assertTrue(clear > .1f); assertTrue(background > 1); assertTrue(opposite); rest(force, 1f / 60);
    }
    @Test public void boundariesAndSmallListsRetainCenteredRest() {
        TaskForce force = graph(); force.drag(-180); assertEquals(0, force.offset, 0); assertTrue(force.tx[0] < 0); force.interrupt(); force.release(0); rest(force, 1f / 60);
        force.drag(force.maximum + 180); assertTrue(force.tx[0] > 0); force.interrupt(); force.release(0); rest(force, 1f / 60);
        force.bounds(245, 245); force.drag(97); force.interrupt(); force.release(0); rest(force, 1f / 60);
        force.bounds(0, 245); force.drag(180); force.interrupt(); force.release(0); rest(force, 1f / 60); assertEquals(0, force.offset, 0);
    }
    @Test public void waitingForCloseDoesNotPollAndFinalAcknowledgementCanResumeCentering() {
        TaskForce force = graph(); force.offset = 97; force.release(0); assertFalse(force.advance(1f / 60, false, false, 1)); assertTrue(force.pending); assertEquals(97, force.offset, 0); rest(force, 1f / 60);
    }
    @Test public void reducedMotionAndCancellationNeverCarryOldMomentumOrDismissal() {
        TaskForce force = graph(); force.offset = 500; force.release(2000); force.interrupt(); assertEquals(0, force.velocity, 0); force.reduced(true, true); assertEquals(500, force.offset, 0); force.reduced(false, true); assertEquals(490, force.offset, 0);
        assertFalse(TaskForce.dismiss(-90, 300, 0, true)); assertTrue(TaskForce.dismiss(-91, 300, 0, true)); assertTrue(TaskForce.dismiss(-12, 300, -600, true)); assertFalse(TaskForce.dismiss(-11, 300, -1600, true)); assertFalse(TaskForce.dismiss(-40, 300, -1600, false)); assertFalse(TaskForce.dismiss(40, 300, -1600, true));
    }
    @Test public void fastFlingCapsCaptureDeformationAndStillCenters() {
        for (float step : new float[]{110, 245, 380}) for (float speed : new float[]{-12000, -5000, 5000, 12000}) {
            TaskForce force = graph(); force.bounds(step * 19, step); force.offset = step * 8.4f; force.release(speed);
            for (int frame = 0; frame < 600; frame++) { force.advance(1f / 60, false, true, 1); if (force.snapping) for (int i = 0; i < 4; i++) assertTrue("fast capture remains within 1.8% of a card step", Math.abs(force.x[i]) <= step * .018f + .001f); }
            rest(force, 1f / 60);
        }
    }
    @Test public void pageEntryLoadsCardsClearAndBackgroundThenStops() {
        TaskForce force = graph(); force.sceneImpulse(0, -2200); float card = 0, clear = 0, background = 0;
        for (int frame = 0; frame < 150; frame++) { force.advance(1f / 60, false, true, 1); card = Math.max(card, Math.abs(force.y[1])); clear = Math.max(clear, Math.abs(force.y[4])); background = Math.max(background, Math.abs(force.y[5])); }
        assertTrue("entry cards move visibly", card > 20); assertTrue("clear shares visible entry force", clear > 12); assertTrue("background reaction is visible", background > 6); rest(force, 1f / 60);
    }
}
