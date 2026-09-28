package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoverRotationTest {
    @Test public void calibratedOrientationSupportsEveryMountAndWraparound() {
        for (int rotation = 0; rotation < 4; rotation++) {
            assertEquals(rotation, CoverRotation.target(350, 350, rotation));
            assertEquals((rotation + 3) % 4, CoverRotation.target(350, 80, rotation));
            assertEquals((rotation + 2) % 4, CoverRotation.target(350, 170, rotation));
            assertEquals((rotation + 1) % 4, CoverRotation.target(350, 260, rotation));
        }
    }
    @Test public void intermediateAndFlatAnglesDoNotTriggerRotation() {
        assertEquals(-1, CoverRotation.target(0, -1, 0)); assertEquals(-1, CoverRotation.target(0, 45, 0)); assertEquals(-1, CoverRotation.target(0, 130, 0)); assertEquals(0, CoverRotation.target(0, 20, 0));
    }
}
