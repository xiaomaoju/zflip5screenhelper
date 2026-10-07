package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class WidgetSafeAreaTest {
    @Test public void quarterTurnUsesMeasuredScreenWhenSamsungKeepsUnrotatedFullScreenOptions() {
        float density = 2.125f;
        var canvas = WidgetSafeArea.canvas(352, 339, 720 / density, 748 / density, true);
        var safe = new DockGeometry.Box(66, 72, 654, 630);
        assertEquals(720 / density, canvas.width(), .001f);
        assertEquals(748 / density, canvas.height(), .001f);
        var frame = WidgetSafeArea.fit(canvas.width(), canvas.height(), 720, 748, density, safe);
        assertEquals(safe.x(), frame.left() * density, .001f);
        assertEquals(safe.y(), frame.top() * density, .001f);
        assertEquals(safe.bottom(), (frame.top() + frame.height()) * density, .001f);
    }
    @Test public void canvasKeepsResizedCardsAndNonQuarterTurnsBounded() {
        assertEquals(new WidgetSafeArea.Frame(0, 0, 310, 280), WidgetSafeArea.canvas(310, 280, 339, 352, true));
        assertEquals(new WidgetSafeArea.Frame(0, 0, 339, 339), WidgetSafeArea.canvas(352, 339, 339, 352, false));
        assertEquals(new WidgetSafeArea.Frame(0, 0, 339, 352), WidgetSafeArea.canvas(339, 352, 339, 352, true));
    }
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
    @Test public void tentHostConsumesTopInsetOnceAndKeepsDockInsideItsBottom() {
        float density = 2.125f;
        var safe = new DockGeometry.Box(0, 151, 748, 569);
        var host = new DockGeometry.Box(0, 66, 748, 654);
        var frame = WidgetSafeArea.fit(352, 339, 748, 720, density, safe, host);
        assertEquals(85, frame.top() * density, .001f);
        assertEquals(569, frame.height() * density, .001f);
        assertEquals(654, (frame.top() + frame.height()) * density, .001f);
        assertEquals(151, host.y() + frame.top() * density, .001f);
        assertEquals(frame, WidgetSafeArea.fit(352, 308, 748, 720, density, safe, host));
    }
    @Test public void fullOrUnknownHostKeepsOriginalGeometryExactly() {
        var safe = new DockGeometry.Box(0, 151, 748, 569);
        var original = WidgetSafeArea.fit(352, 339, 748, 720, 2.125f, safe);
        assertEquals(original, WidgetSafeArea.fit(352, 339, 748, 720, 2.125f, safe, null));
        assertEquals(original, WidgetSafeArea.fit(352, 339, 748, 720, 2.125f, safe, new DockGeometry.Box(0, 0, 748, 720)));
        assertEquals(original, WidgetSafeArea.fit(352, 339, 748, 720, 2.125f, safe, new DockGeometry.Box(0, 66, 748, 720)));
    }
    @Test public void smallerCardIsNotRepositionedFromHostWindowAlone() {
        var safe = new DockGeometry.Box(0, 151, 748, 569);
        assertEquals(WidgetSafeArea.fit(300, 200, 748, 720, 2.125f, safe), WidgetSafeArea.fit(300, 200, 748, 720, 2.125f, safe, new DockGeometry.Box(0, 66, 748, 654)));
    }
    @Test public void rotatedHostProjectsPhysicalEdgesWithoutMirroring() {
        float density = 2.125f;
        var canvas = WidgetSafeArea.canvas(352, 339, 720 / density, 748 / density, true);
        var frame = WidgetSafeArea.fit(canvas.width(), canvas.height(), 720, 748, density, new DockGeometry.Box(90, 72, 610, 630), new DockGeometry.Box(66, 0, 654, 748));
        assertEquals(24, frame.left() * density, .001f);
        assertEquals(72, frame.top() * density, .001f);
        assertEquals(610, frame.width() * density, .001f);
        assertEquals(630, frame.height() * density, .001f);
    }
    @Test public void hostWithoutChromeFillsOnlyMeasuredWindowAndDisjointAreaStaysEmpty() {
        var host = new DockGeometry.Box(0, 66, 748, 654);
        var frame = WidgetSafeArea.fit(352, 339, 748, 720, 2.125f, null, host);
        assertEquals(new WidgetSafeArea.Frame(0, 0, 352, 654 / 2.125f), frame);
        for (var safe : new DockGeometry.Box[]{new DockGeometry.Box(0, 0, 748, 60), new DockGeometry.Box(800, 800, 10, 10)}) {
            frame = WidgetSafeArea.fit(352, 339, 748, 720, 2.125f, safe, host);
            assertEquals(0, frame.height(), 0);
            assertTrue(frame.left() + frame.width() <= 352);
            assertTrue(frame.top() + frame.height() <= 654 / 2.125f);
        }
    }
}
