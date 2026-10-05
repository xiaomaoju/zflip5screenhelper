package io.github.flipcover.controls;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class DockGeometryTest {
    @Test public void measuredShortcutGroupCompactsAwayFromCameraAcrossRotations() {
        DockGeometry.Box[] cuts = {new DockGeometry.Box(379, 654, 369, 66), new DockGeometry.Box(654, 0, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(0, 379, 66, 369)};
        for (float density : new float[]{1.5f, 2.125f, 2.75f}) for (int rotation = 0; rotation < 4; rotation++) {
            int width = rotation % 2 == 0 ? 748 : 720, height = rotation % 2 == 0 ? 720 : 748;
            int margin = Math.max(4, Math.round(3 * density));
            DockGeometry.Placement dock = DockGeometry.resolve(width, height, List.of(cuts[rotation]), density, (rotation + 3) % 4, .46f, .088f, true);
            DockGeometry.Box visual = dock.visual();
            assertTrue(dock.measured()); assertEquals(new int[]{DockGeometry.BOTTOM, DockGeometry.RIGHT, DockGeometry.TOP, DockGeometry.LEFT}[rotation], dock.edge());
            assertEquals(Math.round((379 - 2 * margin) * .875f), dock.vertical() ? visual.height() : visual.width());
            assertEquals(66 - 2 * margin, dock.vertical() ? visual.width() : visual.height());
            switch (rotation) {
                case 0 -> assertEquals(margin, visual.x());
                case 1 -> assertEquals(height - margin, visual.bottom());
                case 2 -> assertEquals(width - margin, visual.right());
                default -> assertEquals(margin, visual.y());
            }
            DockGeometry.Box touch = dock.touch();
            assertEquals(379 - 2 * margin, dock.vertical() ? touch.height() : touch.width());
            DockGeometry.Placement edge = DockGeometry.edgeTouch(dock, width, height);
            assertEquals(dock.vertical() ? touch.height() : touch.width(), dock.vertical() ? edge.touch().height() : edge.touch().width());
            DockGeometry.Slots slots = DockGeometry.slots(dock, 5);
            assertEquals(4, slots.pageSize());
            assertEquals((dock.vertical() ? visual.height() * touch.width() : visual.width() * touch.height()), slots.pager().width() * slots.pager().height() + slots.fixed().width() * slots.fixed().height());
        }
    }
    @Test public void landscapeStatusKeepsBothSafeEdgesAtTopAcrossScales() {
        for (DockGeometry.Box cut : new DockGeometry.Box[]{new DockGeometry.Box(0, 351, 66, 369), new DockGeometry.Box(682, 0, 66, 369)}) {
            for (float density : new float[]{1.5f, 2.125f, 2.75f}) for (int scale : new int[]{50, 70, 100, 150}) {
                DockGeometry.Placement dock = DockGeometry.resolve(748, 720, List.of(cut), density, 0, .46f, .088f, true);
                DockGeometry.Box area = DockGeometry.panelContent(dock, 748, 720, List.of(cut));
                int height = Math.round(Math.round(20 * density) * scale / 100f);
                DockGeometry.Box status = DockGeometry.statusBar(area, 748, height, density);
                assertEquals(0, status.y()); assertEquals(height, status.height());
                assertEquals(area.x(), status.x()); assertEquals(area.right(), status.right());
            }
        }
    }
    @Test public void portraitStatusRetainsReservedTopAndFullSafeWidth() {
        DockGeometry.Box area = new DockGeometry.Box(0, 100, 720, 580);
        assertEquals(new DockGeometry.Box(0, 100, 720, 30), DockGeometry.statusBar(area, 720, 30, 2.125f));
        assertEquals(new DockGeometry.Box(0, 0, 720, 30), DockGeometry.statusBar(new DockGeometry.Box(0, 0, 720, 630), 720, 30, 2.125f));
        for (int top = 1; top <= 6; top++) assertEquals(new DockGeometry.Box(0, top, 720, 30), DockGeometry.statusBar(new DockGeometry.Box(0, top, 720, 630), 720, 30, 2.125f));
    }
    @Test public void landscapeTouchReachesFirstPixelWithoutMovingLoweredArtwork() {
        for (int rotation : new int[]{1, 3}) for (float density : new float[]{1.5f, 2.125f, 2.75f}) for (int scale : new int[]{50, 70, 100, 150}) for (String position : new String[]{"top_left", "top_right"}) {
            DockGeometry.Box cut = rotation == 1 ? new DockGeometry.Box(0, 351, 66, 369) : new DockGeometry.Box(682, 0, 66, 369);
            DockGeometry.Placement dock = DockGeometry.resolve(748, 720, List.of(cut), density, 0, .46f, .088f, true);
            DockGeometry.Placement old = DockGeometry.panelEntry(dock, dock, 748, 720, List.of(cut), density, 24, position);
            int inset = DockGeometry.panelEntryTopInset(position, scale, density), statusHeight = Math.round(Math.round(20 * density) * scale / 100f);
            DockGeometry.Placement entry = DockGeometry.panelEntry(dock, dock, 748, 720, List.of(cut), density, 24, position, inset);
            assertEquals(0, entry.touch().y()); assertEquals(statusHeight + Math.round((position.equals("top_left") ? 2 : 10) * density), entry.visual().y());
            assertEquals(old.touch().x(), entry.touch().x()); assertEquals(old.touch().width(), entry.touch().width()); assertEquals(entry.visual().y() + Math.round(24 * density), entry.touch().height());
            DockGeometry.Chrome before = DockGeometry.panelEntryChrome(old, density), after = DockGeometry.panelEntryChrome(entry, density);
            assertEquals(before.firstHandle().x(), after.firstHandle().x()); assertEquals(before.secondHandle().x(), after.secondHandle().x());
            assertEquals(before.firstHandle().y() + inset, after.firstHandle().y()); assertEquals(before.secondHandle().y() + inset, after.secondHandle().y());
            assertEquals(before.firstHandle().width(), after.firstHandle().width()); assertEquals(before.firstHandle().height(), after.firstHandle().height());
            assertEquals(entry.touch().bottom(), entry.panel().y());
        }
        assertEquals(0, DockGeometry.panelEntryTopInset("bottom_right", 100, 2.125f));
        assertEquals(Math.round(20 * 2.125f) + Math.round(2 * 2.125f), DockGeometry.panelEntryTopInset("top_left", 100, 2.125f));
    }
    @Test public void invertedStatusStartsAtCameraSafeTopWithEntryBelowIt() {
        DockGeometry.Box cut = new DockGeometry.Box(0, 0, 369, 66);
        for (float density : new float[]{1.5f, 2.125f, 2.75f}) for (int scale : new int[]{50, 70, 100, 150}) {
            DockGeometry.Placement dock = DockGeometry.resolve(748, 720, List.of(cut), density, 1, .46f, .088f, true);
            DockGeometry.Box safe = DockGeometry.panelContent(dock, 748, 720, List.of(cut));
            int height = Math.round(Math.round(20 * density) * scale / 100f);
            DockGeometry.Box status = DockGeometry.statusBar(safe, 748, height, density);
            DockGeometry.Placement entry = DockGeometry.panelEntry(dock, dock, 748, 720, List.of(cut), density, 24, "top_left", DockGeometry.panelEntryTopInset("top_left", scale, density));
            assertEquals(cut.bottom(), status.y()); assertEquals(0, status.x()); assertEquals(748, status.width());
            assertEquals(status.bottom() + Math.round(2 * density), entry.visual().y());
            assertEquals(cut.bottom(), entry.touch().y()); assertEquals(entry.visual().bottom(), entry.panel().y());
        }
    }
    @Test public void hubReclaimsEntryBandButKeepsStatusDockAndHomeBounds() {
        DockGeometry.Box dock = new DockGeometry.Box(6, 660, 367, 60);
        DockGeometry.Placement placement = new DockGeometry.Placement(dock, dock, new DockGeometry.Box(0, 39, 748, 575), DockGeometry.BOTTOM, false);
        assertEquals(new DockGeometry.Box(0, 33, 748, 621), DockGeometry.hubContent(placement, 748, 720, List.of(), new DockGeometry.Box(0, 0, 748, 654)));
        assertEquals(660, DockGeometry.hubContent(placement, 748, 720, List.of(), new DockGeometry.Box(0, 0, 748, 720)).bottom());
        DockGeometry.Box camera = new DockGeometry.Box(390, 630, 358, 90);
        assertEquals(630, DockGeometry.hubContent(placement, 748, 720, List.of(camera), new DockGeometry.Box(0, 0, 748, 720)).bottom());
    }
    @Test public void upsideDownLauncherMovesAboveNavigationWithEveryRegionUnchanged() {
        DockGeometry.Box dock = new DockGeometry.Box(375, 0, 367, 60), camera = new DockGeometry.Box(0, 0, 369, 66);
        DockGeometry.Placement placement = new DockGeometry.Placement(dock, dock, new DockGeometry.Box(0, 151, 748, 569), DockGeometry.TOP, true);
        DockGeometry.Box baseline = DockGeometry.hubContent(placement, 748, 720, List.of(camera), new DockGeometry.Box(0, 0, 748, 720));
        for (int navigation : new int[]{24, 32, 66, 85}) {
            DockGeometry.Box shifted = DockGeometry.hubContent(placement, 748, 720, List.of(camera), new DockGeometry.Box(0, 0, 748, 720 - navigation));
            assertEquals(baseline.y() - navigation, shifted.y()); assertEquals(baseline.height(), shifted.height());
            assertEquals(baseline.width(), shifted.width()); assertEquals(720 - navigation, shifted.bottom()); assertTrue(shifted.y() >= camera.bottom());
            for (float density : new float[]{1.5f, 2.125f, 2.75f}) for (boolean right : new boolean[]{false, true}) for (boolean nativePaging : new boolean[]{false, true}) {
                assertEquals(AppLauncherStyle.hubGeometry(baseline.width(), baseline.height(), density, right, nativePaging), AppLauncherStyle.hubGeometry(shifted.width(), shifted.height(), density, right, nativePaging));
            }
        }
        assertEquals(baseline, DockGeometry.hubContent(placement, 748, 720, List.of(camera), new DockGeometry.Box(0, 0, 748, 720)));
    }
    @Test public void launcherShiftStopsAtPhysicalCutoutWhenThereIsNoMoreRoom() {
        DockGeometry.Box dock = new DockGeometry.Box(375, 0, 367, 60), camera = new DockGeometry.Box(0, 0, 369, 66);
        DockGeometry.Placement placement = new DockGeometry.Placement(dock, dock, new DockGeometry.Box(0, 151, 748, 569), DockGeometry.TOP, true);
        DockGeometry.Box shifted = DockGeometry.hubContent(placement, 748, 720, List.of(camera), new DockGeometry.Box(0, 0, 748, 600));
        assertEquals(camera.bottom(), shifted.y()); assertEquals(600, shifted.bottom()); assertEquals(534, shifted.height());
        DockGeometry.Box bottomCamera = new DockGeometry.Box(379, 654, 369, 66);
        DockGeometry.Placement bottomDock = new DockGeometry.Placement(new DockGeometry.Box(6, 660, 367, 60), dock, new DockGeometry.Box(0, 39, 748, 575), DockGeometry.BOTTOM, true);
        assertEquals(DockGeometry.hubContent(bottomDock, 748, 720, List.of(bottomCamera), new DockGeometry.Box(0, 0, 748, 720)), DockGeometry.hubContent(bottomDock, 748, 720, List.of(bottomCamera), new DockGeometry.Box(0, 0, 748, 654)));
    }
    @Test public void systemEdgesConstrainEveryRotationWithoutDuplicatingCutoutDepths() {
        DockGeometry.Box[] cuts = {new DockGeometry.Box(379, 654, 369, 66), new DockGeometry.Box(654, 0, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(0, 379, 66, 369)};
        for (int rotation = 0; rotation < 4; rotation++) {
            int width = rotation % 2 == 0 ? 748 : 720, height = rotation % 2 == 0 ? 720 : 748;
            DockGeometry.Placement dock = DockGeometry.resolve(width, height, List.of(cuts[rotation]), 2.125f, (rotation + 3) % 4, .46f, .088f, true);
            DockGeometry.Box physical = DockGeometry.panelContent(dock, width, height, List.of(cuts[rotation]));
            for (DockGeometry.Box system : List.of(new DockGeometry.Box(0, 0, width, height - 84), new DockGeometry.Box(84, 0, width - 84, height), new DockGeometry.Box(0, 84, width, height - 84), new DockGeometry.Box(0, 0, width - 84, height), new DockGeometry.Box(3, 24, width - 8, height - 56))) {
                DockGeometry.Box content = physical.intersect(system), hub = DockGeometry.hubContent(dock, width, height, List.of(cuts[rotation]), system);
                assertEquals(Math.max(physical.x(), system.x()), content.x()); assertEquals(Math.max(physical.y(), system.y()), content.y());
                assertEquals(Math.min(physical.right(), system.right()), content.right()); assertEquals(Math.min(physical.bottom(), system.bottom()), content.bottom());
                assertEquals(content, content.intersect(system));
                for (DockGeometry.Box area : List.of(content, hub)) {
                    assertTrue(area.x() >= system.x() && area.y() >= system.y());
                    assertTrue(area.right() <= system.right() && area.bottom() <= system.bottom());
                }
            }
            assertEquals(physical, physical.intersect(new DockGeometry.Box(0, 0, width, height)));
        }
    }
    @Test public void launcherBottomShiftCannotEnterTheSystemTopOrSideRegions() {
        DockGeometry.Box dock = new DockGeometry.Box(375, 0, 367, 60), camera = new DockGeometry.Box(0, 0, 369, 66);
        DockGeometry.Placement placement = new DockGeometry.Placement(dock, dock, new DockGeometry.Box(0, 151, 748, 569), DockGeometry.TOP, true);
        DockGeometry.Box safe = new DockGeometry.Box(24, 100, 688, 500);
        assertEquals(safe, DockGeometry.hubContent(placement, 748, 720, List.of(camera), safe));
        assertEquals(new DockGeometry.Box(24, 151, 688, 569), DockGeometry.hubContent(placement, 748, 720, List.of(camera), new DockGeometry.Box(24, 100, 688, 620)));
    }
    @Test public void selectedCornersRemainHorizontalAndClearOfCameraDockAndHome() {
        DockGeometry.Box[] cuts = {new DockGeometry.Box(379, 654, 369, 66), new DockGeometry.Box(654, 0, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(0, 379, 66, 369)};
        for (float density : new float[]{2.125f, 2.75f}) for (int home : new int[]{0, 24, 90}) for (int rotation = 0; rotation < 4; rotation++) for (String position : new String[]{"top_left", "top_right", "bottom_right"}) {
            if (!Prefs.validEntryPosition(rotation, position)) continue;
            int width = rotation % 2 == 0 ? 748 : 720, height = rotation % 2 == 0 ? 720 : 748, margin = Math.max(4, Math.round(3 * density));
            DockGeometry.Placement dock = DockGeometry.resolve(width, height, List.of(cuts[rotation]), density, (rotation + 3) % 4, .46f, .088f, true);
            DockGeometry.Placement entry = DockGeometry.panelEntry(dock, dock, width, height, List.of(cuts[rotation]), density, home, position, DockGeometry.panelEntryTopInset(position, 70, density));
            DockGeometry.Box strip = entry.touch(), cut = cuts[rotation], button = dock.touch(); boolean top = !position.equals("bottom_right");
            assertEquals(top ? DockGeometry.TOP : DockGeometry.BOTTOM, entry.edge()); assertFalse(entry.vertical()); assertTrue(entry.measured());
            assertTrue(strip.width() > strip.height()); assertTrue(strip.x() >= 0 && strip.y() >= 0 && strip.right() <= width && strip.bottom() <= height);
            assertTrue(strip.right() <= cut.x() || strip.x() >= cut.right() || strip.bottom() <= cut.y() || strip.y() >= cut.bottom());
            assertTrue(strip.right() <= button.x() || strip.x() >= button.right() || strip.bottom() <= button.y() || strip.y() >= button.bottom());
            if (position.equals("top_left")) assertTrue(strip.x() < width / 2); else assertTrue(strip.right() > width / 2);
            if (top) { assertTrue(strip.y() < height / 2); assertEquals(strip.bottom(), entry.panel().y()); }
            else { assertEquals(cut.x() + margin, strip.x()); assertEquals(Math.min(cut.y(), height - home), strip.bottom()); assertEquals(strip.y(), entry.panel().bottom()); }
            DockGeometry.Chrome chrome = DockGeometry.panelEntryChrome(entry, density);
            assertEquals(chrome.firstHandle().y(), chrome.secondHandle().y()); assertTrue(chrome.firstHandle().width() > chrome.firstHandle().height());
        }
        assertFalse(Prefs.validEntryPosition(0, "top_right")); assertFalse(Prefs.validEntryPosition(2, "top_right"));
        assertFalse(Prefs.validEntryPosition(1, "bottom_right")); assertFalse(Prefs.validEntryPosition(3, "bottom_right"));
    }
    @Test public void missingCutoutStillKeepsBottomUpwardEntryWithCalibratedNormalPosture() {
        for (int corner = 0; corner < 4; corner++) {
            DockGeometry.Placement dock = DockGeometry.resolve(720, 748, List.of(), 2.125f, corner, .46f, .088f, false);
            DockGeometry.Placement entry = DockGeometry.panelEntry(dock, dock, 720, 748, List.of(), 2.125f, 90, "bottom_right");
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
            DockGeometry.Box entry = DockGeometry.panelEntry(original, moved, 748, 720, List.of(cut), 2.125f, 24, "top_right").touch(), touch = moved.touch();
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
