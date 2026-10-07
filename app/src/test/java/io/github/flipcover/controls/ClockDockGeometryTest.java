package io.github.flipcover.controls;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClockDockGeometryTest {
    @Test public void arrowAvoidsCameraDockAndSystemInsetsInEveryRotation() {
        DockGeometry.Box[] cuts = {new DockGeometry.Box(379, 654, 369, 66), new DockGeometry.Box(654, 0, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(0, 379, 66, 369)};
        for (float density : new float[]{1.5f, 2.125f, 2.75f}) for (int rotation = 0; rotation < 4; rotation++) {
            int width = rotation % 2 == 0 ? 748 : 720, height = rotation % 2 == 0 ? 720 : 748;
            DockGeometry.Placement dock = DockGeometry.resolve(width, height, List.of(cuts[rotation]), density, (rotation + 3) % 4, .46f, .088f, true);
            dock = DockGeometry.edgeTouch(dock, width, height);
            DockGeometry.Box safe = new DockGeometry.Box(24, 30, width - 48, height - 78);
            DockGeometry.Box handle = DockGeometry.clockHandle(dock, width, height, List.of(cuts[rotation]), density, safe, new DockGeometry.Box(0, 0, 0, 0));
            assertTrue(handle.width() > 0 && handle.height() > 0);
            assertEquals(handle, handle.intersect(safe));
            assertFalse(overlaps(handle, cuts[rotation])); assertFalse(overlaps(handle, dock.touch()));
            assertTrue(handle.width() <= Math.round(48 * density)); assertTrue(handle.height() <= Math.round(48 * density));
        }
    }
    @Test public void arrowReportsNoSpaceInsteadOfCrossingBounds() {
        DockGeometry.Box strip = new DockGeometry.Box(0, 0, 100, 60);
        DockGeometry.Placement dock = new DockGeometry.Placement(strip, strip, strip, DockGeometry.TOP, false);
        DockGeometry.Box handle = DockGeometry.clockHandle(dock, 100, 60, List.of(), 2, strip, new DockGeometry.Box(0, 0, 0, 0));
        assertEquals(0, handle.height());
    }
    @Test public void sameCornerArrowCannotStealWhiteBarGestures() {
        for (int corner = 0; corner < 4; corner++) for (String entry : List.of("top_left", "top_right", "bottom_right")) {
            DockGeometry.Placement dock = DockGeometry.edgeTouch(DockGeometry.resolve(720, 748, List.of(), 2.125f, corner, .46f, .088f, false), 720, 748);
            DockGeometry.Placement bars = DockGeometry.panelEntry(dock, dock, 720, 748, List.of(), 2.125f, 24, entry, DockGeometry.panelEntryTopInset(entry, 100, 2.125f));
            DockGeometry.Box handle = DockGeometry.clockHandle(dock, 720, 748, List.of(), 2.125f, new DockGeometry.Box(0, 40, 720, 684), bars.touch());
            assertTrue(handle.width() > 0 && handle.height() > 0);
            assertFalse(overlaps(handle, bars.touch())); assertFalse(overlaps(handle, dock.touch()));
        }
    }
    @Test public void collapsedArrowOccupiesDockAndExpandedArrowMovesInwardInEveryDirection() {
        DockGeometry.Box[] cuts = {new DockGeometry.Box(379, 654, 369, 66), new DockGeometry.Box(654, 0, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(0, 379, 66, 369)};
        for (int rotation = 0; rotation < 4; rotation++) {
            int width = rotation % 2 == 0 ? 748 : 720, height = rotation % 2 == 0 ? 720 : 748;
            DockGeometry.Placement dock = DockGeometry.edgeTouch(DockGeometry.resolve(width, height, List.of(cuts[rotation]), 2.125f, (rotation + 3) % 4, .46f, .088f, true), width, height);
            DockGeometry.Box safe = new DockGeometry.Box(0, 0, width, height);
            DockGeometry.Box open = DockGeometry.clockHandle(dock, width, height, List.of(cuts[rotation]), 2.125f, safe, new DockGeometry.Box(0, 0, 0, 0));
            DockGeometry.ClockHandle points = DockGeometry.clockHandlePositions(dock, open, safe);
            DockGeometry.Box outside = DockGeometry.outsideDisplay(points.collapsed(), dock.edge(), width, height);
            assertFalse(overlaps(outside, safe));
            assertEquals(points.collapsed(), DockGeometry.interpolate(outside, points.collapsed(), 1));
            assertEquals(points.collapsed(), points.collapsed().intersect(dock.visual()));
            assertFalse(overlaps(points.expanded(), dock.touch()));
            assertEquals(points.collapsed().width(), points.expanded().width()); assertEquals(points.collapsed().height(), points.expanded().height());
            assertEquals(points.collapsed(), DockGeometry.interpolate(points.collapsed(), points.expanded(), 0));
            assertEquals(points.expanded(), DockGeometry.interpolate(points.collapsed(), points.expanded(), 1));
            DockGeometry.Box mid = DockGeometry.interpolate(points.collapsed(), points.expanded(), .5f);
            assertEquals(Math.round((points.collapsed().x() + points.expanded().x()) / 2f), mid.x());
            assertEquals(Math.round((points.collapsed().y() + points.expanded().y()) / 2f), mid.y());
            assertFalse(overlaps(mid, cuts[rotation]));
        }
    }
    private boolean overlaps(DockGeometry.Box a, DockGeometry.Box b) { DockGeometry.Box intersection = a.intersect(b); return intersection.width() > 0 && intersection.height() > 0; }
}
