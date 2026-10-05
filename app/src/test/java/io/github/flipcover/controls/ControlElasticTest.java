package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class ControlElasticTest {
    @Test public void onlyGridMovesUntilItsLimitThenWholePageStretches() {
        ControlElastic spring = new ControlElastic(); spring.drag(40); assertTrue(spring.grid() > 0); assertEquals(0, spring.whole(), 0);
        spring.drag(50); assertEquals(ControlElastic.LIMIT, spring.grid(), .0001f); assertTrue(spring.whole() > 0);
        spring.drag(500); assertEquals(ControlElastic.MAXIMUM, spring.load, 0); assertEquals(.055f, spring.whole(), .0001f);
    }
    @Test public void bothEdgesRestoreTogetherBeforeWholePageIsFinished() {
        for (int edge : new int[]{1, -1}) {
            ControlElastic spring = new ControlElastic(); spring.drag(edge * 154); float grid = spring.grid(), whole = spring.whole(); spring.release(0); spring.advance(.016f);
            assertTrue(Math.abs(spring.grid()) < Math.abs(grid)); assertTrue(spring.whole() > 0); assertTrue(spring.whole() < whole);
            assertEquals(spring.grid() / grid, spring.whole() / whole, .00001f);
        }
    }
    @Test public void frozenRegrabAndReversalPreserveTheDisplayedPosition() {
        ControlElastic spring = new ControlElastic(); spring.drag(154); spring.release(0); spring.advance(.032f); float grid = spring.grid(); spring.freeze(); assertEquals(grid, spring.grid(), 0);
        spring.drag(2); assertTrue(Math.abs(spring.grid() - grid) < 2); spring.drag(-500); assertEquals(-ControlElastic.LIMIT, spring.grid(), .0001f); assertEquals(-1, spring.edge());
    }
    @Test public void extremeVelocitiesAndFrameIntervalsSettleWithoutGrowingState() {
        for (float seconds : new float[]{.008f, .016f, .032f, .5f}) for (float speed : new float[]{-2000, 0, 2000}) {
            ControlElastic spring = new ControlElastic(); spring.drag(ControlElastic.MAXIMUM); spring.release(speed);
            for (int i = 0; i < 600 && spring.moving(); i++) { spring.advance(seconds); assertTrue(Float.isFinite(spring.grid())); assertTrue(Math.abs(spring.grid()) <= ControlElastic.LIMIT + .001f); }
            assertFalse(spring.moving()); assertEquals(0, spring.grid(), 0); assertEquals(0, spring.whole(), 0);
        }
    }
}
