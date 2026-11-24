package es.yori.sleeptrain.domain;

import es.yori.sleeptrain.data.CatalogCodec;
import org.json.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.Assert.*;

public class TransitLineTest {
    public static Map<String, TransitLine> catalog() throws Exception {
        JSONArray json = new JSONObject(new String(Files.readAllBytes(Paths.get("src/main/assets/transit_catalog.json")), StandardCharsets.UTF_8)).getJSONArray("lines");
        Map<String, TransitLine> result = new HashMap<>(); for (int i = 0; i < json.length(); i++) { TransitLine line = CatalogCodec.decode(json.getJSONObject(i)); result.put(line.id, line); } return result;
    }
    @Test public void completeC5AndMetroAreSeparateNamespaces() throws Exception {
        Map<String, TransitLine> lines = catalog(); assertEquals(23, lines.get(TransitLine.RENFE).stations.size()); assertEquals(19, lines.get(TransitLine.METRO).stations.size());
        assertEquals("35012", lines.get(TransitLine.RENFE).stations.get(22).id);
        assertTrue(lines.get(TransitLine.RENFE).trips.values().stream().allMatch(t -> t.routeId.startsWith("10")));
    }
    @Test public void slicesWorkInBothDirectionsAndIncludePreviousDestination() throws Exception {
        TransitLine line = catalog().get(TransitLine.RENFE);
        assertEquals("18000", line.slice("18000", "35607").get(0).id);
        assertEquals("35606", line.slice("18000", "35607").get(11 - 1).id);
        assertEquals(11, line.segments("18000", "35607").length);
        assertEquals(line.segments("18000", "35607")[0], line.segments("35607", "18000")[10]);
    }
    @Test public void serviceDatesAndTimesAfterMidnightAreRespected() throws Exception {
        TransitLine reference = catalog().get(TransitLine.RENFE);
        TransitLine.ScheduledTrip trip = new TransitLine.ScheduledTrip("night", "route", Arrays.asList("35607", "18000"), new int[]{90000, 91800}, new int[]{1, 12}, Collections.singleton("20261115"), false);
        TransitLine line = new TransitLine(reference.id, reference.name, reference.stations, reference.segments("35607", "35012"), Collections.singleton(trip), reference.routeIds, reference.agencyIds, "", "", "", "");
        long now = LocalDate.of(2026, 11, 16).atTime(1, 0).atZone(TransitLine.ZONE).toInstant().toEpochMilli();
        assertEquals(now, line.departures("35607", "18000", now).get(0).time);
        assertTrue(line.departures("18000", "35607", now).isEmpty()); assertTrue(line.departures("35607", "18000", now + 24 * 3600_000L).isEmpty());
    }
    @Test public void incompleteMetroGtfsDoesNotInventTrains() throws Exception {
        TransitLine metro = catalog().get(TransitLine.METRO); assertTrue(metro.trips.isEmpty()); assertFalse(metro.realtimeAvailable);
        assertTrue(metro.notice.contains("sin") || metro.notice.contains("Sin"));
        assertTrue(metro.departures("par_4_53", "par_4_46", System.currentTimeMillis()).isEmpty());
    }
    @Test(expected = IllegalArgumentException.class) public void unknownStationIsRejected() throws Exception { catalog().get(TransitLine.RENFE).slice("par_4_46", "35607"); }
}
