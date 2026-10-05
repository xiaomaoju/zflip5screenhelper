package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class TaskSpringTest {
    @Test public void returnHasTwoDiminishingReboundsAndExactEndpoints() {
        assertEquals(0, TaskSpring.progress(0), 0);
        assertEquals(1, TaskSpring.progress(1), 0);
        float first = TaskSpring.progress(.2f) - 1, second = 1 - TaskSpring.progress(.4f), third = TaskSpring.progress(.6f) - 1;
        assertTrue(first > .25f && second > .07f && third > .02f);
        assertTrue(first > second && second > third);
        for (int frame = 0; frame <= 1000; frame++) {
            float progress = TaskSpring.progress(frame / 1000f);
            assertTrue(Float.isFinite(progress) && progress >= 0 && progress < 1.35f);
        }
    }
    @Test public void velocityResponseStartsContinuouslyAndSharesDiminishingReturns() {
        assertEquals(0, TaskSpring.velocityOffset(0), 0); assertEquals(0, TaskSpring.velocityOffset(1), 0);
        float time = .00001f; assertEquals(1, TaskSpring.velocityOffset(time) / (time * TaskSpring.DURATION / 1000f), .001f);
        assertTrue(TaskSpring.velocityOffset(.1f) > 0); assertTrue(TaskSpring.velocityOffset(.3f) < 0); assertTrue(TaskSpring.velocityOffset(.5f) > 0);
        assertTrue(Math.abs(TaskSpring.velocityOffset(.1f)) > Math.abs(TaskSpring.velocityOffset(.3f)));
        assertTrue(Math.abs(TaskSpring.velocityOffset(.3f)) > Math.abs(TaskSpring.velocityOffset(.5f)));
    }
}
