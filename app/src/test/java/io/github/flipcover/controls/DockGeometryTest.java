package io.github.flipcover.controls;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class DockGeometryTest {
    @Test public void hubReclaimsEntryBandButKeepsStatusDockAndHomeBounds() {
        DockGeometry.Box dock = new DockGeometry.Box(6, 660, 367, 60);
        DockGeometry.Placement placement = new DockGeometry.Placement(dock, dock, new DockGeometry.Box(0, 39, 748, 575), DockGeometry.BOTTOM, false);
        assertEquals(new DockGeometry.Box(0, 39, 748, 615), DockGeometry.hubContent(placement, 748, 720, List.of(), 66));
        assertEquals(660, DockGeometry.hubContent(placement, 748, 720, List.of(), 0).bottom());
        DockGeometry.Box camera = new DockGeometry.Box(390, 630, 358, 90);
        assertEquals(630, DockGeometry.hubContent(placement, 748, 720, List.of(camera), 0).bottom());
    }
    @Test public void panelHandlesRemainHorizontalAtBottomRightAcrossAllRotations() {
        DockGeometry.Box[] cuts = {new DockGeometry.Box(351, 682, 369, 66), new DockGeometry.Box(0, 351, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(682, 0, 66, 369)};
        for (float density : new float[]{2.125f, 2.75f}) for (int home : new int[]{0, 24, 90}) for (int rotation = 0; rotation < 4; rotation++) {
            int width = rotation % 2 == 0 ? 720 : 748, height = rotation % 2 == 0 ? 748 : 720, margin = Math.max(4, Math.round(3 * density));
            DockGeometry.Placement dock = DockGeometry.resolve(width, height, List.of(cuts[rotation]), density, (rotation + 3) % 4, .46f, .088f, true);
            DockGeometry.Placement entry = DockGeometry.panelEntry(dock, dock, width, height, List.of(cuts[rotation]), density, home);
            DockGeometry.Box strip = entry.touch(), cut = cuts[rotation];
            assertEquals(DockGeometry.BOTTOM, entry.edge()); assertFalse(entry.vertical()); assertTrue(entry.measured());
            assertTrue(strip.width() > strip.height()); assertTrue(strip.x() >= width / 2 - margin);
            assertTrue(strip.y() >= 0 && strip.right() <= width && strip.bottom() <= height - home);
            assertTrue(strip.right() <= cut.x() || strip.x() >= cut.right() || strip.bottom() <= cut.y() || strip.y() >= cut.bottom());
            assertTrue(strip.right() <= dock.touch().x() || strip.x() >= dock.touch().right() || strip.bottom() <= dock.touch().y() || strip.y() >= dock.touch().bottom());
            assertEquals(strip.y(), entry.panel().bottom());
            assertEquals(Math.min(rotation == 0 ? cut.y() : height, height - home), strip.bottom());
            if (rotation == 0) assertTrue(strip.x() > cut.x());
            DockGeometry.Chrome chrome = DockGeometry.panelEntryChrome(entry, density);
            assertEquals(chrome.firstHandle().y(), chrome.secondHandle().y());
            assertTrue(chrome.firstHandle().width() > chrome.firstHandle().height());
            assertEquals(Math.round(density), strip.height() - chrome.firstHandle().bottom());
            assertEquals(Math.round(density), strip.height() - chrome.secondHandle().bottom());
        }
    }
    @Test public void missingCutoutStillKeepsBottomUpwardEntryWithCalibratedNormalPosture() {
        for (int corner = 0; corner < 4; corner++) {
            DockGeometry.Placement dock = DockGeometry.resolve(720, 748, List.of(), 2.125f, corner, .46f, .088f, false);
            DockGeometry.Placement entry = DockGeometry.panelEntry(dock, dock, 720, 748, List.of(), 2.125f, 90);
            assertFalse(entry.measured()); assertEquals(DockGeometry.BOTTOM, entry.edge()); assertFalse(entry.vertical());
            assertTrue(entry.touch().width() > entry.touch().height());
            assertTrue(entry.touch().x() >= 0 && entry.touch().y() >= 0 && entry.touch().right() <= 720 && entry.touch().bottom() <= 658);
        }
    }
    @Test public void entryAvoidsActualDockAfterNavigationInsetMovesItInward() {
        DockGeometry.Box cut = new DockGeometry.Box(682, 0, 66, 369);
        DockGeometry.Placement original = DockGeometry.resolve(748, 720, List.of(cut), 2.125f, 2, .46f, .088f, true);
        for (int gap : new int[]{0, 16, 48}) {
            DockGeometry.Placement moved = DockGeometry.avoidEdge(original, 748, 720, 48, gap);
            DockGeometry.Box entry = DockGeometry.panelEntry(original, moved, 748, 720, List.of(cut), 2.125f, 24).touch(), touch = moved.touch();
            assertTrue(entry.right() <= touch.x() || entry.x() >= touch.right() || entry.bottom() <= touch.y() || entry.y() >= touch.bottom());
            assertTrue(entry.width() > entry.height());
        }
    }
    @Test public void handleArtworkKeepsEdgeAndIconClearanceWithoutMovingTouchBounds() {
        for (float density : new float[]{1.5f, 2.125f, 2.75f}) for (int corner = 0; corner < 4; corner++) {
            DockGeometry.Placement p = DockGeometry.edgeTouch(DockGeometry.resolve(720, 748, List.of(), density, corner, .46f, .088f, false), 720, 748);
            DockGeometry.Box original = p.touch(); DockGeometry.Chrome chrome = DockGeometry.chrome(p, density);
            DockGeometry.Box a = chrome.firstHandle(), b = chrome.secondHandle(), icons = chrome.icons();
            assertEquals(original, p.touch());
            for (DockGeometry.Box bar : new DockGeometry.Box[]{a, b}) {
                assertTrue(bar.x() >= 0 && bar.y() >= 0 && bar.right() <= original.width() && bar.bottom() <= original.height());
                int outer = switch (p.edge()) { case DockGeometry.TOP -> bar.y(); case DockGeometry.LEFT -> bar.x(); case DockGeometry.RIGHT -> original.width() - bar.right(); default -> original.height() - bar.bottom(); };
                int gap = switch (p.edge()) { case DockGeometry.TOP -> icons.y() - bar.bottom(); case DockGeometry.LEFT -> icons.x() - bar.right(); case DockGeometry.RIGHT -> bar.x() - icons.right(); default -> bar.y() - icons.bottom(); };
                assertEquals(Math.round(5 * density), outer); assertTrue(gap >= Math.round(3 * density));
            }
            assertEquals(a.width(), b.width()); assertEquals(a.height(), b.height());
            int length = p.vertical() ? p.visual().height() : p.visual().width();
            assertTrue((p.vertical() ? a.height() : a.width()) < length * .4f);
            assertTrue(p.vertical() ? a.bottom() < b.y() : a.right() < b.x());
            DockGeometry.Placement collapsed = DockGeometry.handlesOnly(p, density); DockGeometry.Chrome compact = DockGeometry.chrome(collapsed, density);
            for (int half = 0; half < 2; half++) {
                DockGeometry.Box expandedBar = half == 0 ? a : b, compactBar = half == 0 ? compact.firstHandle() : compact.secondHandle();
                assertEquals(expandedBar.x() + original.x(), compactBar.x() + collapsed.touch().x());
                assertEquals(expandedBar.y() + original.y(), compactBar.y() + collapsed.touch().y());
                assertEquals(expandedBar.width(), compactBar.width()); assertEquals(expandedBar.height(), compactBar.height());
            }
        }
    }
    @Test public void edgeEntryTouchesFirstPixelAndContentUsesFormerTouchGap() {
        for (int corner = 0; corner < 4; corner++) {
            DockGeometry.Placement old = DockGeometry.resolve(720, 748, List.of(), 2.125f, corner, .46f, .088f, false);
            DockGeometry.Placement edge = DockGeometry.edgeTouch(old, 720, 748), compact = DockGeometry.handlesOnly(edge, 2.125f);
            int outer = switch (edge.edge()) { case DockGeometry.TOP -> edge.touch().y(); case DockGeometry.LEFT -> edge.touch().x(); case DockGeometry.RIGHT -> 720 - edge.touch().right(); default -> 748 - edge.touch().bottom(); };
            int collapsedOuter = switch (edge.edge()) { case DockGeometry.TOP -> compact.touch().y(); case DockGeometry.LEFT -> compact.touch().x(); case DockGeometry.RIGHT -> 720 - compact.touch().right(); default -> 748 - compact.touch().bottom(); };
            assertEquals(0, outer); assertEquals(0, collapsedOuter); assertEquals(old.visual(), edge.visual());
            DockGeometry.Box content = DockGeometry.panelContent(edge, 720, 748, List.of());
            assertTrue(content.width() * content.height() > old.panel().width() * old.panel().height());
            if (edge.edge() == DockGeometry.BOTTOM) assertEquals(edge.visual().y(), content.bottom());
            if (edge.edge() == DockGeometry.TOP) assertEquals(edge.visual().bottom(), content.y());
        }
    }
    @Test public void navigationAvoidanceMovesInwardOnEveryEdgeWithoutTouchingCutout() {
        DockGeometry.Box[] cuts = {new DockGeometry.Box(351, 682, 369, 66), new DockGeometry.Box(0, 351, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(682, 0, 66, 369)};
        for (int rotation = 0; rotation < 4; rotation++) {
            int w = rotation % 2 == 0 ? 720 : 748, h = rotation % 2 == 0 ? 748 : 720;
            DockGeometry.Placement original = DockGeometry.resolve(w, h, List.of(cuts[rotation]), 2.125f, (rotation + 3) % 4, .46f, .088f, true);
            DockGeometry.Placement moved = DockGeometry.avoidEdge(original, w, h, 48, 16); DockGeometry.Box t = moved.touch(), v = moved.visual(), cut = cuts[rotation];
            int clearance = switch (moved.edge()) { case DockGeometry.TOP -> v.y(); case DockGeometry.LEFT -> v.x(); case DockGeometry.RIGHT -> w - v.right(); default -> h - v.bottom(); };
            assertTrue(clearance >= 64); assertEquals(original.edge(), moved.edge()); assertEquals(original.touch().width(), t.width()); assertEquals(original.touch().height(), t.height());
            assertTrue(t.x() >= 0 && t.y() >= 0 && t.right() <= w && t.bottom() <= h);
            assertTrue(t.right() <= cut.x() || t.x() >= cut.right() || t.bottom() <= cut.y() || t.y() >= cut.bottom());
            DockGeometry.Placement same = DockGeometry.avoidEdge(moved, w, h, 48, 16); assertEquals(moved, same);
        }
    }
    @Test public void handedSlotsStayInsideSafeDockAcrossRotationsAndSizes() {
        for (int size : new int[]{480, 720, 1080}) for (int corner = 0; corner < 4; corner++) for (int count = 2; count <= 5; count++) for (boolean first : new boolean[]{false, true}) {
            DockGeometry.Placement p = DockGeometry.resolve(size, size + 28, List.of(), 2.125f, corner, .46f, .088f, false);
            DockGeometry.Slots slots = DockGeometry.slots(p, count, first); DockGeometry.Box a = slots.pager(), b = slots.fixed();
            assertEquals(p.touch().width() * p.touch().height(), a.width() * a.height() + b.width() * b.height());
            assertTrue(a.x() >= 0 && a.y() >= 0 && b.x() >= 0 && b.y() >= 0);
            assertTrue(a.right() <= p.touch().width() && b.right() <= p.touch().width() && a.bottom() <= p.touch().height() && b.bottom() <= p.touch().height());
            if (p.vertical()) assertEquals(first ? b.bottom() : a.bottom(), first ? a.y() : b.y());
            else assertEquals(first ? b.right() : a.right(), first ? a.x() : b.x());
        }
    }
    @Test public void collapsedTouchAreaIsOnlyTheThinStripOnEveryEdge() {
        for (int corner = 0; corner < 4; corner++) {
            DockGeometry.Placement full = DockGeometry.resolve(720, 748, List.of(), 2, corner, .46f, .088f, false);
            DockGeometry.Placement compact = DockGeometry.handlesOnly(full, 2);
            assertEquals(compact.visual(), compact.touch());
            assertEquals(full.panel(), compact.panel());
            assertEquals(32, compact.vertical() ? compact.touch().width() : compact.touch().height());
            assertTrue(compact.touch().x() >= full.visual().x() && compact.touch().y() >= full.visual().y());
            assertTrue(compact.touch().right() <= full.visual().right() && compact.touch().bottom() <= full.visual().bottom());
        }
    }
    @Test public void folderTailHasNoTouchOverlapWithCameraCutout() {
        DockGeometry.Placement p = DockGeometry.resolve(720, 748, List.of(new DockGeometry.Box(351, 682, 369, 66)), 2.125f, 3, .46f, .088f, true);
        assertTrue(p.measured()); assertEquals(DockGeometry.BOTTOM, p.edge());
        assertTrue(p.visual().right() < 351);
        assertTrue(p.touch().y() < p.visual().y());
        assertTrue(p.panel().bottom() < p.touch().y());
    }
    @Test public void measuredTailFollowsAllFourRotations() {
        DockGeometry.Box[] cutouts = {
            new DockGeometry.Box(351, 682, 369, 66), new DockGeometry.Box(0, 351, 66, 369),
            new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(682, 0, 66, 369)
        };
        for (int rotation = 0; rotation < 4; rotation++) {
            int width = rotation % 2 == 0 ? 720 : 748, height = rotation % 2 == 0 ? 748 : 720;
            DockGeometry.Placement p = DockGeometry.resolve(width, height, List.of(cutouts[rotation]), 2.125f, (rotation + 3) % 4, .46f, .088f, true);
            assertTrue(p.measured()); assertEquals(rotation, p.edge());
            assertTrue(p.touch().x() >= 0 && p.touch().y() >= 0);
            assertTrue(p.touch().right() <= width && p.touch().bottom() <= height);
            DockGeometry.Box cutout = cutouts[rotation], touch = p.touch();
            assertTrue(touch.right() <= cutout.x() || touch.x() >= cutout.right() || touch.bottom() <= cutout.y() || touch.y() >= cutout.bottom());
        }
    }
    @Test public void fullWidthCutoutUsesSafeFallback() {
        DockGeometry.Placement p = DockGeometry.resolve(720, 748, List.of(new DockGeometry.Box(0, 682, 720, 66)), 2.125f, 3, .46f, .088f, true);
        assertFalse(p.measured()); assertTrue(p.visual().bottom() < 682);
    }
    @Test public void manualAnchorsStayOnScreenInEveryOrientation() {
        for (int corner = 0; corner < 4; corner++) {
            DockGeometry.Placement p = DockGeometry.resolve(720, 748, List.of(), 2.125f, corner, .46f, .088f, false);
            assertTrue(p.touch().x() >= 0); assertTrue(p.touch().y() >= 0);
            assertTrue(p.touch().right() <= 720); assertTrue(p.touch().bottom() <= 748);
        }
    }
    @Test public void twoToFiveSlotsReserveExactlyOneFixedButtonOnEveryEdge() {
        for (int corner = 0; corner < 4; corner++) {
            DockGeometry.Placement placement = DockGeometry.resolve(720, 748, List.of(), 2.125f, corner, .46f, .088f, false);
            for (int count = 2; count <= 5; count++) {
                DockGeometry.Slots slots = DockGeometry.slots(placement, count);
                assertEquals(count - 1, slots.pageSize());
                assertEquals(placement.touch().width() * placement.touch().height(), slots.pager().width() * slots.pager().height() + slots.fixed().width() * slots.fixed().height());
                if (placement.vertical()) {
                    assertEquals(slots.pager().bottom(), slots.fixed().y());
                    assertEquals(placement.touch().height(), slots.fixed().bottom());
                } else {
                    assertEquals(slots.pager().right(), slots.fixed().x());
                    assertEquals(placement.touch().width(), slots.fixed().right());
                }
            }
        }
    }
}
