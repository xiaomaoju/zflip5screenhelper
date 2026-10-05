package io.github.flipcover.controls;

import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class DockVisibilityPerformanceTest {
    private static final class Rules extends AbstractSet<String> {
        private final Set<String> values;
        int lookups;
        Rules(String... values) { this.values = Set.of(values); }
        @Override public Iterator<String> iterator() { return values.iterator(); }
        @Override public int size() { return values.size(); }
        @Override public boolean contains(Object value) { lookups++; return values.contains(value); }
    }
    @Test public void repeatedEventsInTheSameAppMatchTheListOnlyOnce() {
        DockVisibility state = new DockVisibility(); Rules rules = new Rules("selected");
        state.foreground("selected");
        for (int i = 0; i < 100; i++) { state.foreground(new String("selected")); assertTrue(state.compact(true, rules)); }
        assertEquals(1, rules.lookups);
        state.foreground("other");
        for (int i = 0; i < 100; i++) assertFalse(state.compact(true, rules));
        assertEquals(2, rules.lookups);
    }
    @Test public void emptyAndDisabledRulesNeverMatchPackages() {
        DockVisibility state = new DockVisibility(); Rules empty = new Rules(), selected = new Rules("selected");
        for (int i = 0; i < 100; i++) { state.foreground("app" + i); assertFalse(state.compact(true, empty)); assertFalse(state.compact(false, selected)); }
        assertEquals(0, empty.lookups); assertEquals(0, selected.lookups);
    }
    @Test public void changedSnapshotsAndDisplayResetRecomputeWithoutChangingManualChoices() {
        DockVisibility state = new DockVisibility(); Rules selected = new Rules("app"), removed = new Rules();
        state.foreground("app"); assertTrue(state.compact(true, selected));
        assertFalse(state.compact(true, removed));
        Rules other = new Rules("other"); assertFalse(state.compact(true, other)); assertEquals(1, other.lookups);
        state.toggle(true, other); assertTrue(state.compact(true, other));
        state.keyboard(true); state.toggle(true, other); assertFalse(state.compact(true, other));
        assertEquals(1, other.lookups);
        state.rulesChanged(); assertFalse(state.compact(true, other)); assertEquals(1, other.lookups);
        state.keyboard(false); assertTrue(state.compact(true, other));
        state.reset(); assertTrue(state.compact(true, selected));
        state.foreground("app"); assertTrue(state.compact(true, selected)); assertEquals(2, selected.lookups);
    }
}
