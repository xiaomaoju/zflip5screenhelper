package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class WidgetContentLayoutTest {
    @Test public void fixedHeightFitsWithoutStretchingOrCropping() {
        var fit = WidgetContentLayout.fit(352, 280, 352, 322);
        assertEquals(280f / 322, fit.scale(), .0001f);
        assertEquals(352, fit.width() * fit.scale(), .001f);
        assertEquals(280, fit.height() * fit.scale(), .001f);
        assertTrue(fit.height() >= 322);
    }
    @Test public void flexibleContentKeepsItsAllocatedSize() {
        assertEquals(new WidgetContentLayout.Fit(352, 280, 1), WidgetContentLayout.fit(352, 280, 320, 200));
    }
    @Test public void narrowCellScalesBothAxesTogether() {
        var fit = WidgetContentLayout.fit(160, 280, 320, 322);
        assertEquals(.5f, fit.scale(), 0);
        assertEquals(160, fit.width() * fit.scale(), 0);
        assertEquals(280, fit.height() * fit.scale(), 0);
    }
    @Test public void nativeTopReservesStatusAndArtworkOnce() {
        var safe = new DockGeometry.Box(0, 66, 748, 654);
        var dock = new DockGeometry.Placement(new DockGeometry.Box(375, 0, 367, 60), new DockGeometry.Box(375, 0, 367, 60), safe, DockGeometry.TOP, true);
        var cuts = java.util.List.of(new DockGeometry.Box(0, 0, 369, 66));
        var entry = DockGeometry.panelEntry(dock, dock, 748, 720, cuts, 2.125f, 0, "top_left", DockGeometry.panelEntryTopInset("top_left", 100, 2.125f));
        var frame = DockGeometry.widgetContent(dock, entry, new DockGeometry.Box(0, 66, 748, 42), 748, 720, cuts, 2.125f);
        assertTrue(frame.y() >= 108);
        assertTrue(frame.y() < entry.panel().y());
        assertEquals(720, frame.bottom());
        assertEquals(748, frame.width());
    }
}
