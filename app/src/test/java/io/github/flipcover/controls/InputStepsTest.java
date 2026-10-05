package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class InputStepsTest {
    @Test public void smallMovementAccumulatesAndResetDropsResidual() { InputSteps steps = new InputSteps(); assertEquals(0, steps.move(10, 0, 28)); assertEquals(0, steps.move(10, 0, 28)); assertEquals(66, steps.move(10, 0, 28)); steps.reset(); assertEquals(0, steps.move(26, 0, 28)); }
    @Test public void dominantAxisDoesNotProduceTwoCommands() { InputSteps steps = new InputSteps(); assertEquals(130, steps.move(30, 50, 28)); assertEquals(0, steps.move(0, 0, 28)); }
    @Test public void reversalAndNonFiniteInputAreSafe() { InputSteps steps = new InputSteps(); steps.move(20, 0, 28); assertEquals(0, steps.move(-20, 0, 28)); assertEquals(17, steps.move(-30, 0, 28)); assertEquals(0, steps.move(Float.NaN, 50, 28)); assertEquals(0, steps.move(0, 0, 28)); }
    @Test public void axisSwitchAndVerticalDirections() { InputSteps steps = new InputSteps(); assertEquals(66, steps.move(40, 1, 28)); assertEquals(33, steps.move(0, -60, 28)); steps.reset(); assertEquals(130, steps.move(0, 28, 28)); }
}
