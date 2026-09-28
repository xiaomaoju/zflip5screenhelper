package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class SwipeGestureTest {
    @Test public void shortDragCancelsInsteadOfClicking() {
        SwipeGesture g = new SwipeGesture(6, 24);
        assertEquals(-3, g.move(-12, 0), .01);
        assertEquals(SwipeGesture.Result.CANCEL, g.finish());
    }
    @Test public void directTapAndBothPagingDirections() {
        SwipeGesture g = new SwipeGesture(6, 24);
        assertEquals(SwipeGesture.Result.TAP, g.finish());
        g.move(-60, 2);
        assertEquals(SwipeGesture.Result.NEXT, g.finish());
        g.reset(); g.move(60, 2);
        assertEquals(SwipeGesture.Result.PREVIOUS, g.finish());
    }
    @Test public void verticalMistakeDoesNotTurnPage() {
        SwipeGesture g = new SwipeGesture(6, 24);
        g.move(3, 40); g.move(50, 50);
        assertEquals(SwipeGesture.Result.CANCEL, g.finish());
    }
    @Test public void ReturningBelowThresholdSpringsBack() {
        SwipeGesture g = new SwipeGesture(6, 24);
        g.move(-80, 0); g.move(-10, 0);
        assertEquals(SwipeGesture.Result.CANCEL, g.finish());
    }
}
