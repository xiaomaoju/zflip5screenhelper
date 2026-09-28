package io.github.flipcover.controls;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class WidgetGridTest {
    @Test public void acceptsFourIndependentQuartersAndRejectsOverlap() {
        var a = new WidgetGrid.Item(10, 0, 0, 2, 2);
        var b = WidgetGrid.find(List.of(a), 11, 2, 2);
        var c = WidgetGrid.find(List.of(a, b), 12, 2, 2);
        var d = WidgetGrid.find(List.of(a, b, c), 13, 2, 2);
        var full = List.of(a, b, c, d);
        assertTrue(WidgetGrid.valid(full)); assertEquals(16, WidgetGrid.used(full)); assertNull(WidgetGrid.find(full, 14, 1, 1));
        assertFalse(WidgetGrid.fits(full, a.at(1, 0))); assertTrue(WidgetGrid.fits(full, a));
    }
    @Test public void emptyAreaMustBeContiguous() {
        var fragmented = List.of(new WidgetGrid.Item(1, 1, 0, 1, 4), new WidgetGrid.Item(2, 3, 0, 1, 4));
        assertEquals(8, WidgetGrid.used(fragmented)); assertNull(WidgetGrid.find(fragmented, 3, 2, 2)); assertNotNull(WidgetGrid.find(fragmented, 3, 1, 4));
    }
    @Test public void gridBoundsAndIdentitiesAreValidated() {
        for (int x = -1; x <= 4; x++) for (int y = -1; y <= 4; y++) for (int w = 0; w <= 5; w++) for (int h = 0; h <= 5; h++) {
            var item = new WidgetGrid.Item(1, x, y, w, h);
            assertEquals(x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= 4 && y + h <= 4, WidgetGrid.valid(List.of(item)));
        }
        assertFalse(WidgetGrid.valid(List.of(new WidgetGrid.Item(1, 0, 0, 1, 1), new WidgetGrid.Item(1, 1, 0, 1, 1))));
        assertFalse(WidgetGrid.valid(List.of(new WidgetGrid.Item(0, 0, 0, 1, 1)))); assertNull(WidgetGrid.find(List.of(), 1, Integer.MIN_VALUE, 2));
    }
    @Test public void changingDraftDoesNotMutateOriginal() {
        var original = List.of(new WidgetGrid.Item(1, 0, 0, 2, 2)); var changed = WidgetGrid.replace(original, original.get(0).at(2, 2));
        assertEquals(0, original.get(0).x()); assertEquals(2, changed.get(0).x()); assertEquals(changed, WidgetGrid.unpack(WidgetGrid.pack(changed)));
        assertTrue(WidgetGrid.unpack(new int[] {1, 2}).isEmpty());
    }
}
