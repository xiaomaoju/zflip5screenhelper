package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class MediaSessionsTest {
    @Test public void extrapolatesPlayingPositionWithinDuration() { assertEquals(2500, MediaSessions.positionAt(1000, 500, 1.5f, true, 6000, 1500)); assertEquals(2000, MediaSessions.positionAt(1000, 500, 1.5f, true, 2000, 1500)); }
    @Test public void pausedAndFutureTimestampsDoNotAdvance() { assertEquals(1000, MediaSessions.positionAt(1000, 500, 1, false, 6000, 1500)); assertEquals(1000, MediaSessions.positionAt(1000, 2000, 1, true, 6000, 1500)); }
    @Test public void invalidOrReverseSpeedCannotProduceNegativePosition() { assertEquals(1000, MediaSessions.positionAt(1000, 500, Float.NaN, true, 0, 1500)); assertEquals(0, MediaSessions.positionAt(1000, 500, -2, true, 0, 1500)); }
}
