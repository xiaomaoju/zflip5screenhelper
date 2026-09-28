package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class WidgetSafeAreaTest {
    @Test public void normalPostureKeepsContentAboveControlsAndBelowStatus() {
        var frame = WidgetSafeArea.fit(352, 339, 720, 748, 2, new DockGeometry.Box(0, 48, 720, 604));
        assertEquals(new WidgetSafeArea.Frame(0, 24, 352, 267), frame);
        assertTrue(frame.inset(352, 339));
    }
    @Test public void physicalSafeAreaMovesToEachReportedEdge() {
        // Insets are already in current display coordinates: bottom, right, top, left.
        var areas = new DockGeometry.Box[] {
            new DockGeometry.Box(0, 0, 720, 648), new DockGeometry.Box(0, 0, 648, 720),
            new DockGeometry.Box(0, 72, 720, 648), new DockGeometry.Box(72, 0, 648, 720)
        };
        var expected = new WidgetSafeArea.Frame[] {
            new WidgetSafeArea.Frame(0, 0, 300, 264), new WidgetSafeArea.Frame(0, 0, 264, 300),
            new WidgetSafeArea.Frame(0, 36, 300, 264), new WidgetSafeArea.Frame(36, 0, 264, 300)
        };
        for (int rotation = 0; rotation < 4; rotation++) assertEquals(expected[rotation], WidgetSafeArea.fit(300, 300, 720, 720, 2, areas[rotation]));
    }
    @Test public void areaBelongsToWholeGridAndDoesNotMirrorCameraSide() {
        var frame = WidgetSafeArea.fit(300, 300, 720, 748, 2, new DockGeometry.Box(40, 48, 660, 620));
        assertEquals(new WidgetSafeArea.Frame(20, 24, 270, 236), frame);
        assertEquals(frame.width(), frame.width() / 4 * 4, .001f);
        assertEquals(frame.top() + frame.height(), frame.top() + frame.height() / 4 * 4, .001f);
    }
    @Test public void absentChromeDoesNotInventInsets() {
        assertEquals(new WidgetSafeArea.Frame(0, 0, 300, 300), WidgetSafeArea.fit(300, 300, 720, 748, 2, null));
    }
    @Test public void insufficientAreaStaysEmptyRatherThanOverflowing() {
        var frame = WidgetSafeArea.fit(30, 20, 720, 748, 2, new DockGeometry.Box(0, 48, 720, 604));
        assertEquals(0, frame.height(), 0); assertTrue(frame.top() <= 20); assertTrue(frame.width() >= 0);
    }
}
