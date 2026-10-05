package io.github.flipcover.controls;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationForceTest {
    @Test public void continuedPullAlwaysMovesFartherWithIncreasingResistance() {
        float previous = 0, previousIncrease = Float.MAX_VALUE;
        for (int distance = 40; distance <= 800; distance += 40) {
            float value = NotificationForce.pull(distance, 120), increase = value - previous;
            assertTrue(value > previous && value < 120); assertTrue(increase < previousIncrease);
            previous = value; previousIncrease = increase;
        }
        assertEquals(0, NotificationForce.pull(0, 120), 0);
    }
    @Test public void topPullMovesEveryCardDownAndOpensTheGaps() {
        float previous = 0;
        for (float top : new float[]{0, 140, 280, 420}) {
            float shift = NotificationForce.translation(-65) + NotificationForce.card(-65, -1, top, 130, 477);
            assertTrue(shift >= 65 * .72f && shift <= 65 && shift > previous); previous = shift;
        }
    }
    @Test public void bottomPullMovesEveryCardUpWithMoreStretchTowardsTheEarlierCards() {
        float previous = -Float.MAX_VALUE;
        for (float top : new float[]{-10, 130, 270, 347}) {
            float shift = NotificationForce.translation(65) + NotificationForce.card(65, 1, top, 130, 477);
            assertTrue(shift < 0 && shift >= -65 && shift > previous); previous = shift;
        }
    }
    @Test public void catchingReboundRestoresTheSamePullAndReleaseVelocity() {
        for (float maximum : new float[]{40, 80, 120}) for (float distance : new float[]{1, 40, 96, 200, 400}) {
            float displacement = NotificationForce.pull(distance, maximum);
            assertEquals(distance, NotificationForce.distance(displacement, maximum), .01f);
            float derivative = (NotificationForce.pull(distance + .1f, maximum) - displacement) / .1f;
            assertEquals(derivative, NotificationForce.slope(displacement, maximum), .001f);
        }
    }
    @Test public void viewportPositionRatherThanListLengthDeterminesStretch() {
        assertEquals(NotificationForce.card(-40, -1, 150, 130, 477), NotificationForce.card(-40, -1, 150, 240, 477), 0);
        assertEquals(11.2f, NotificationForce.card(-40, -1, 100000, 130, 477), .001f);
        assertEquals(0, NotificationForce.card(-40, -1, -100000, 130, 477), 0);
    }
    @Test public void reboundKeepsTheAnchorAndRestoresExactStaticGeometry() {
        for (int edge : new int[]{-1, 1}) for (float top : new float[]{0, 140, 350}) {
            assertEquals(-NotificationForce.card(60, edge, top, 130, 477), NotificationForce.card(-60, edge, top, 130, 477), 0);
            assertEquals(0, NotificationForce.card(0, edge, top, 130, 477), 0);
        }
        assertEquals(0, NotificationForce.translation(0), 0);
    }
    private void step(NotificationForce.Spring[] nodes, float drive) {
        for (int sub = 0; sub < 4; sub++) {
            for (int i = 0; i < nodes.length; i++) nodes[i].load(NotificationForce.scrollTarget(drive, i * 140, 130, 600));
            for (int i = 1; i < nodes.length; i++) NotificationForce.connect(nodes[i - 1], nodes[i]);
            for (NotificationForce.Spring node : nodes) node.advance(1 / 240f);
        }
    }
    @Test public void ordinaryScrollProducesDifferentCoupledCardDisplacements() {
        NotificationForce.Spring[] nodes = {new NotificationForce.Spring(), new NotificationForce.Spring(), new NotificationForce.Spring(), new NotificationForce.Spring()};
        for (int frame = 0; frame < 25; frame++) step(nodes, 30);
        assertTrue(nodes[0].position > 4 && nodes[1].position > nodes[0].position && nodes[2].position > nodes[1].position && nodes[3].position > nodes[2].position);
        for (NotificationForce.Spring node : nodes) assertTrue(node.position < 39);
    }
    @Test public void neighboringCardReceivesForceWithoutItsOwnInput() {
        NotificationForce.Spring a = new NotificationForce.Spring(), b = new NotificationForce.Spring(); a.position = 15;
        a.load(0); b.load(0); NotificationForce.connect(a, b); b.advance(1 / 60f);
        assertTrue(b.position > 0 && b.velocity > 0);
    }
    @Test public void ordinaryScrollReturnsWithoutAnIdleFrameLoop() {
        NotificationForce.Spring[] nodes = {new NotificationForce.Spring(), new NotificationForce.Spring(), new NotificationForce.Spring()};
        for (int frame = 0; frame < 12; frame++) step(nodes, -38);
        assertTrue(nodes[0].position < -4);
        for (int frame = 0; frame < 120; frame++) step(nodes, 0);
        for (NotificationForce.Spring node : nodes) { assertFalse(node.moving()); node.reset(); assertEquals(0, node.position, 0); }
    }
    @Test public void staticCardsDoNotMoveWithoutUserScrollInput() {
        NotificationForce.Spring[] nodes = {new NotificationForce.Spring(), new NotificationForce.Spring()};
        for (int frame = 0; frame < 60; frame++) step(nodes, 0);
        for (NotificationForce.Spring node : nodes) assertEquals(0, node.position, 0);
    }
}
