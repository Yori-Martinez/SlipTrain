package es.yori.sleeptrain.data;

import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.domain.TransitLine;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class GtfsImporterTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private TransitLine reference(boolean metro) {
        return new TransitLine(metro ? TransitLine.METRO : TransitLine.RENFE, "test", Arrays.asList(new Station("A", "A", 40.4, -3.7), new Station("B", "B", 40.3, -3.8)), new int[]{120}, Collections.emptyList(), Collections.singleton(metro ? "4__3___" : "10TC5"), Collections.singleton("agency"), "", "", "", "");
    }
    private void entry(ZipOutputStream zip, String name, String body) throws Exception { zip.putNextEntry(new ZipEntry(name)); zip.write(body.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    private File fixture(boolean metro, boolean omit, boolean noTrips) throws Exception {
        File file = folder.newFile(); String route = metro ? "4__3___" : "10TC5";
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            entry(zip, "stops.txt", "\ufeffstop_id,stop_name,stop_lat,stop_lon   \r\nA,\"Estación, A\",40.4,-3.7   \r\n" + (omit ? "" : "B,Estación B,40.3,-3.8\r\n"));
            entry(zip, "routes.txt", "route_id,route_short_name\n" + route + "," + (metro ? "3" : "C5") + "\n30TC5,C5\n");
            entry(zip, "trips.txt", "route_id,service_id,trip_id\n" + (noTrips ? "" : route + ",s,trip\n"));
            entry(zip, "stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence   \r\n" + (noTrips ? "" : "trip,25:00:00,25:00:00,A,1   \r\ntrip,25:02:00,25:02:00,B,2\r\n"));
            entry(zip, "calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\ns,0,0,0,0,0,1,0,20261114,20261115\n");
            entry(zip, "calendar_dates.txt", "service_id,date,exception_type\ns,20261114,2\ns,20261115,1\n");
        } return file;
    }
    @Test public void importsQuotedPaddedUnicodeCalendarExceptionsAndLateHours() throws Exception {
        JSONObject json = GtfsImporter.parse(fixture(false, false, false), reference(false));
        assertEquals("Estación, A", json.getJSONArray("stations").getJSONObject(0).getString("name"));
        JSONObject trip = json.getJSONArray("trips").getJSONObject(0);
        assertEquals(1, trip.getJSONArray("dates").length()); assertEquals("20261115", trip.getJSONArray("dates").getString(0));
        assertEquals(90000, trip.getJSONArray("times").getInt(0)); assertEquals(120, json.getJSONArray("segmentSeconds").getInt(0));
    }
    @Test public void metroWithoutTripsIsExplicitlyAnEstimate() throws Exception {
        JSONObject json = GtfsImporter.parse(fixture(true, false, true), reference(true));
        assertEquals(0, json.getJSONArray("trips").length()); assertTrue(json.getString("notice").contains("provisionales"));
    }
    @Test(expected = IOException.class) public void incompleteSourceCannotReplaceValidCatalog() throws Exception { GtfsImporter.parse(fixture(false, true, false), reference(false)); }
}
