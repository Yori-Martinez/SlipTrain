package es.yori.sleeptrain;

import es.yori.sleeptrain.core.Station;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MovementEvidenceTest {
    private static final long START = 100_000;
    private final MovementEvidence movement = new MovementEvidence(List.of(
            new Station("A", "Origen", 40.40, -3.70), new Station("B", "Siguiente", 40.40, -3.69),
            new Station("C", "Tercera", 40.40, -3.68), new Station("D", "Destino", 40.40, -3.67)));

    @Test public void scheduledTimeAndStationaryFixesDoNotStartATrip() {
        assertFalse(observe(-3.70, 0, 0));
        assertFalse(observe(-3.70, 15, 30_000));
        assertFalse(observe(-3.70, 14, 60_000));
    }

    @Test public void coherentFastMotionAlongFirstSegmentStartsATrip() {
        assertFalse(observe(-3.70, 0, 0));
        assertTrue(observe(-3.696, 16, 25_000));
    }

    @Test public void briefFixesAtThreeSuccessiveStationsCanStartWithoutSpeed() {
        assertFalse(observe(-3.70, Float.NaN, 0));
        assertFalse(observe(-3.69, Float.NaN, 205_000));
        assertTrue(observe(-3.68, Float.NaN, 410_000));
    }

    @Test public void walkingAndStaleOrOffRouteGpsDoNotQualify() {
        assertFalse(observe(-3.70, 1, 0));
        assertFalse(observe(-3.696, 1, 180_000));
        assertFalse(movement.observe(40.40, -3.686, 8, 25, START + 210_000, START + 240_000));
        assertFalse(movement.observe(40.41, -3.689, 8, 14, START + 240_000, START + 240_000));
    }

    @Test public void reverseDirectionDoesNotQualify() {
        assertFalse(observe(-3.69, 12, 0));
        assertFalse(observe(-3.70, 14, 60_000));
        assertFalse(observe(-3.702, 14, 75_000));
    }

    private boolean observe(double longitude, float speed, long millisSinceStart) {
        long now = START + millisSinceStart;
        return movement.observe(40.40, longitude, 8, speed, now, now);
    }
}
