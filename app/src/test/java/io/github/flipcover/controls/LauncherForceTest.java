package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class LauncherForceTest {
    private void rest(LauncherForce force, float dt) { boolean active = true; for (int i = 0; i < 1200 && active; i++) active = force.advance(dt); assertFalse("stops at rest", active); }
    @Test public void entryAndRapidReversalStayBoundedAndStopAtDifferentFrameRates() {
        for (float dt : new float[]{1f / 120, 1f / 60, 1f / 30, .09f}) {
            LauncherForce force = new LauncherForce();
            for (int i = 0; i < 80; i++) { force.scene(i % 2 == 0 ? 80 : -80, i < 40 ? -200 : 200); force.page(i % 2 == 0 ? 150 : -150); force.advance(dt); for (int n = 0; n < LauncherForce.COUNT; n++) { assertTrue(Float.isFinite(force.x[n])); assertTrue(Float.isFinite(force.y[n])); assertTrue(Math.abs(force.x[n]) <= 7); assertTrue(force.scaleX(n) >= .95f && force.scaleX(n) <= 1.066f); } }
            force.release(); rest(force, dt); for (int n = 0; n < LauncherForce.COUNT; n++) { assertEquals(0, force.x[n], 0); assertEquals(0, force.y[n], 0); assertEquals(0, force.vx[n], 0); }
        }
    }
    @Test public void aHeldSidebarReachesEquilibriumWithoutKeepingTheClockRunning() {
        LauncherForce force = new LauncherForce(); force.sidebar(180); rest(force, 1f / 60); assertTrue(force.y[LauncherForce.SIDEBAR] > 0); assertTrue(Math.abs(force.y[LauncherForce.SETTING]) > .05f);
        force.release(); rest(force, 1f / 60); assertEquals(0, force.y[LauncherForce.SIDEBAR], 0);
        force.sidebar(-180); rest(force, 1f / 60); assertTrue(force.y[LauncherForce.SIDEBAR] < 0); force.reset(); assertFalse(force.advance(1f / 60));
    }
    @Test public void regionPressTransmitsToOtherRegionsWithoutIconNodes() {
        LauncherForce force = new LauncherForce(); assertEquals(8, force.x.length); assertEquals(8, LauncherForce.FROM.length); force.impulse(LauncherForce.HEADER, 0, 180); float header = 0, catalog = 0, grid = 0;
        for (int frame = 0; frame < 120; frame++) { force.advance(1f / 60); header = Math.max(header, Math.abs(force.y[LauncherForce.HEADER])); catalog = Math.max(catalog, Math.abs(force.y[LauncherForce.CATALOG])); grid = Math.max(grid, Math.abs(force.y[LauncherForce.GRID])); }
        assertTrue(header > 1); assertTrue(catalog > .05f); assertTrue(grid > .02f); rest(force, 1f / 60);
    }
    @Test public void rubberResistanceTightensAndCannotGrowUnbounded() {
        assertEquals(0, LauncherForce.rubber(0, 5), 0); assertEquals(-LauncherForce.rubber(20, 5), LauncherForce.rubber(-20, 5), .001f); assertTrue(LauncherForce.rubber(2000, 5) <= 5);
        assertTrue(LauncherForce.rubber(30, 5) - LauncherForce.rubber(20, 5) < LauncherForce.rubber(10, 5));
    }
    @Test public void heldEquilibriumPreservesLinkedNodesAcrossRepeatedFrames() {
        LauncherForce force = new LauncherForce(); force.sidebar(180); rest(force, 1f / 60); float catalog = force.y[LauncherForce.CATALOG]; assertTrue(catalog > .1f);
        for (int frame = 0; frame < 120; frame++) { force.sidebar(180); assertFalse(force.advance(1f / 60)); assertEquals(catalog, force.y[LauncherForce.CATALOG], .005f); }
        force.release(); rest(force, 1f / 60); assertEquals(0, force.y[LauncherForce.CATALOG], 0);
    }
    @Test public void crossingVelocityAxesCannotFlipTheStretch() {
        LauncherForce force = new LauncherForce(); force.vx[LauncherForce.GRID] = 100; force.vy[LauncherForce.GRID] = 99; float x = force.scaleX(LauncherForce.GRID), y = force.scaleY(LauncherForce.GRID);
        force.vy[LauncherForce.GRID] = 101; assertEquals(x, force.scaleX(LauncherForce.GRID), .002f); assertEquals(y, force.scaleY(LauncherForce.GRID), .002f);
    }
}
