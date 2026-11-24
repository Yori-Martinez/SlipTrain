package es.yori.sleeptrain.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class StationMatcherTest {
    private final List<Station> stops = Arrays.asList(new Station("A", "Atocha", 40.4066191, -3.6894391),
            new Station("B", "Móstoles", 40.3286626, -3.8636221));

    @Test public void twoDistinctAccurateFixesConfirmStation() {
        StationMatcher matcher = new StationMatcher();
        assertEquals(-1, matcher.observe(stops, 40.3286626, -3.8636221, 40, 10_000, 10_001));
        assertEquals(1, matcher.observe(stops, 40.3286626, -3.8636221, 40, 15_000, 15_001));
    }
    @Test public void duplicatedCachedFixIsNotCorroboration() {
        StationMatcher matcher = new StationMatcher();
        matcher.observe(stops, 40.3286626, -3.8636221, 40, 10_000, 10_001);
        assertEquals(-1, matcher.observe(stops, 40.3286626, -3.8636221, 40, 10_000, 15_000));
    }
    @Test public void rejectsStaleImpreciseFutureAndInvalidPositions() {
        StationMatcher matcher = new StationMatcher();
        assertEquals(-1, matcher.observe(stops, 40.3286626, -3.8636221, 10, 10_000, 50_001));
        assertEquals(-1, matcher.observe(stops, 40.3286626, -3.8636221, 300, 50_000, 50_001));
        assertEquals(-1, matcher.observe(stops, 40.3286626, -3.8636221, 10, 60_000, 50_001));
        assertEquals(-1, matcher.observe(stops, Double.NaN, -3.8636221, 10, 50_000, 50_001));
    }
    @Test public void stationOutsideCorridorIsIgnored() {
        assertEquals(-1, new StationMatcher().observe(stops, 37.3, -6.2, 10, 10_000, 10_000));
    }
    @Test public void disconnectedEvidenceDoesNotConfirmStation() {
        StationMatcher matcher = new StationMatcher();
        matcher.observe(stops, 40.3286626, -3.8636221, 40, 10_000, 10_001);
        assertEquals(-1, matcher.observe(stops, 40.3286626, -3.8636221, 40, 50_000, 50_001));
    }
    @Test public void singlePreciseFixAtDoorOpeningImmediatelyConfirmsStation() {
        assertEquals(1, new StationMatcher().observe(stops, 40.3286626, -3.8636221, 10, 10_000, 10_001));
    }
    @Test public void duplicateStrongFixIsNotReused() {
        StationMatcher matcher = new StationMatcher();
        assertEquals(1, matcher.observe(stops, 40.3286626, -3.8636221, 10, 10_000, 10_000));
        assertEquals(-1, matcher.observe(stops, 40.3286626, -3.8636221, 10, 10_000, 15_000));
    }
    @Test public void accurateFixInCorridorProjectsBetweenStations() {
        StationMatcher.Fix fix = new StationMatcher().match(stops, (40.4066191 + 40.3286626) / 2,
                (-3.6894391 - 3.8636221) / 2, 10, 10_000, 10_000);
        assertNotNull(fix); assertEquals(.5, fix.position, .001); assertEquals(-1, fix.stationIndex);
    }
    @Test public void teleportsAndImpossibleCoordinatesAreRejected() {
        StationMatcher matcher = new StationMatcher(); matcher.observe(stops, 40.4066191, -3.6894391, 10, 10_000, 10_000);
        assertEquals(-1, matcher.observe(stops, 40.3286626, -3.8636221, 10, 11_000, 11_000));
        assertNull(matcher.match(stops, 100, -3.6, 10, 12_000, 12_000));
        assertNull(matcher.match(stops, 40.4, -200, 10, 12_000, 12_000));
    }
    @Test public void ambiguousNearbyStationsAndOffRouteFixCannotConfirmProgress() {
        List<Station> close = Arrays.asList(new Station("A", "A", 40, -3), new Station("B", "B", 40.0001, -3));
        assertNull(new StationMatcher().match(close, 40.00005, -3, 25, 10_000, 10_000));
        assertNull(new StationMatcher().match(stops, 40.4, -3.9, 10, 10_000, 10_000));
    }
}
