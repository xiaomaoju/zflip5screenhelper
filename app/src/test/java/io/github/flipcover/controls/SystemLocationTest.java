package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SystemLocationTest {
    private static final class Switch implements SystemLocation.Access {
        int state, reads; boolean accepted = true, apply = true; final List<Boolean> writes = new ArrayList<>();
        Switch(int state) { this.state = state; }
        public int read() { reads++; return state; }
        public boolean write(boolean enabled) { writes.add(enabled); if (accepted && apply) state = enabled ? 1 : 0; return accepted; }
    }
    @Test public void toggleUsesFreshSystemStateOnEveryClick() throws Exception {
        Switch access = new Switch(0);
        assertEquals(1, SystemLocation.change(-1, access).state());
        access.state = 0; // User disabled location in system settings between clicks.
        assertEquals(1, SystemLocation.change(-1, access).state());
        assertEquals(0, SystemLocation.change(-1, access).state());
        assertEquals(List.of(true, true, false), access.writes);
        assertEquals(6, access.reads);
    }
    @Test public void unknownStateNeverGuessesAToggle() throws Exception {
        Switch access = new Switch(-1);
        SystemLocation.Result result = SystemLocation.change(-1, access);
        assertFalse(result.ok()); assertEquals(-1, result.state()); assertTrue(access.writes.isEmpty());
    }
    @Test public void explicitChoiceCanRecoverUnknownState() throws Exception {
        Switch access = new Switch(-1);
        assertTrue(SystemLocation.change(0, access).ok());
        assertEquals(List.of(false), access.writes); assertEquals(2, access.reads);
    }
    @Test public void alreadyRequestedStateDoesNotWrite() throws Exception {
        Switch access = new Switch(1);
        assertTrue(SystemLocation.change(1, access).ok()); assertTrue(access.writes.isEmpty()); assertEquals(1, access.reads);
    }
    @Test public void rejectedWriteRetainsActualStateWithoutClaimingSuccess() throws Exception {
        Switch access = new Switch(1); access.accepted = false;
        SystemLocation.Result result = SystemLocation.change(0, access);
        assertFalse(result.ok()); assertEquals(1, result.state()); assertEquals(2, access.reads);
    }
    @Test public void acceptedCommandRequiresReadbackConfirmation() throws Exception {
        Switch access = new Switch(0); access.apply = false;
        SystemLocation.Result result = SystemLocation.change(1, access);
        assertFalse(result.ok()); assertEquals(0, result.state()); assertEquals(2, access.reads);
    }
    @Test public void unreadableConfirmationNeverClaimsSuccess() throws Exception {
        Switch access = new Switch(-1); access.apply = false;
        SystemLocation.Result result = SystemLocation.change(1, access);
        assertFalse(result.ok()); assertEquals(-1, result.state());
    }
    @Test public void invalidValuesNeverTouchSystem() {
        for (int value : new int[]{-2, 2, Integer.MAX_VALUE}) {
            Switch access = new Switch(1);
            assertThrows(IllegalArgumentException.class, () -> SystemLocation.change(value, access));
            assertEquals(0, access.reads); assertTrue(access.writes.isEmpty());
        }
    }
    @Test public void lostSessionDuringReadStopsBeforeWrite() {
        List<Boolean> writes = new ArrayList<>();
        SystemLocation.Access access = new SystemLocation.Access() {
            public int read() { throw new IllegalStateException("screen locked"); }
            public boolean write(boolean enabled) { writes.add(enabled); return true; }
        };
        assertThrows(IllegalStateException.class, () -> SystemLocation.change(-1, access));
        assertTrue(writes.isEmpty());
    }
    @Test public void shellOutputOnlyAcceptsAnExactSuccessfulBoolean() {
        assertEquals(1, SystemLocation.state(true, "true\n")); assertEquals(0, SystemLocation.state(true, " false \n"));
        for (String value : new String[]{null, "", "1", "0", "TRUE", "Permission denial", "true\nError: failed", "false\ntrue"}) assertEquals(-1, SystemLocation.state(true, value));
        assertEquals(-1, SystemLocation.state(false, "true"));
    }
    @Test public void locationIsOneSharedAction() {
        assertTrue(ActionCatalog.valid("location"));
        assertEquals(1, ActionCatalog.BUILT_INS.stream().filter(action -> action.id().equals("location")).count());
    }
}
