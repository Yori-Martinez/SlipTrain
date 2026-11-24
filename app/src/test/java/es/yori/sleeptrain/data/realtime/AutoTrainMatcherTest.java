package es.yori.sleeptrain.data.realtime;

import com.google.transit.realtime.GtfsRealtime.*;
import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.domain.*;
import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

public class AutoTrainMatcherTest {
    private final long now = LocalDate.of(2026, 11, 18).atTime(12, 0).atZone(TransitLine.ZONE).toInstant().toEpochMilli();
    private final List<Station> route = Arrays.asList(new Station("A", "A", 40, -3.70), new Station("B", "B", 40, -3.69), new Station("C", "C", 40, -3.68));
    private TransitLine.ScheduledTrip trip(String id, String routeId, List<String> stops, boolean frequency) {
        int[] seconds = new int[stops.size()], seq = new int[stops.size()];
        for (int i = 0; i < seconds.length; i++) { seconds[i] = 12 * 3600 + i * 120; seq[i] = 10 + i; }
        return new TransitLine.ScheduledTrip(id, routeId, stops, seconds, seq, Collections.singleton("20261118"), frequency);
    }
    private TransitLine.ScheduledTrip trip(String id) { return trip(id, "Madrid-C5", Arrays.asList("A", "B", "C"), false); }
    private TransitLine line(TransitLine.ScheduledTrip... trips) {
        return new TransitLine(TransitLine.RENFE, "C5", route, new int[]{120, 120}, Arrays.asList(trips), Collections.singleton("Madrid-C5"),
                Collections.singleton("Renfe"), "", "", "20261215", "");
    }
    private AutoTrainMatcher.DeviceFix gps(int station, long offset) {
        Station s = route.get(station);
        return new AutoTrainMatcher.DeviceFix(s.latitude, s.longitude, 10, station, station, 100_000 + offset, now + offset);
    }
    private VehiclePosition vehicle(String id, int station, long offset, boolean coordinates) {
        VehiclePosition.Builder v = VehiclePosition.newBuilder().setTrip(TripDescriptor.newBuilder().setTripId(id).setRouteId("Madrid-C5").setStartDate("20261118"))
                .setTimestamp((now + offset) / 1000).setStopId(route.get(station).id).setCurrentStatus(VehiclePosition.VehicleStopStatus.STOPPED_AT);
        if (coordinates) v.setPosition(Position.newBuilder().setLatitude((float) route.get(station).latitude).setLongitude((float) route.get(station).longitude));
        return v.build();
    }
    private FeedIndex feed(long offset, VehiclePosition... vehicles) throws Exception {
        FeedMessage.Builder message = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp((now + offset) / 1000));
        for (VehiclePosition v : vehicles) message.addEntity(FeedEntity.newBuilder().setId(v.getTrip().getTripId()).setVehicle(v));
        FeedIndex index = new FeedIndex(); index.accept(message.build().toByteArray(), now + offset); return index;
    }
    private TransitLine.Departure observe(AutoTrainMatcher m, TransitLine line, int station, long offset, VehiclePosition... vehicles) throws Exception {
        return m.match(line, route, feed(offset, vehicles), gps(station, offset), now + offset, 100_000 + offset);
    }
    private AutoTrainMatcher associated() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine line = line(trip("train"));
        assertNull(observe(m, line, 0, 0, vehicle("train", 0, 0, true)));
        assertNotNull(observe(m, line, 1, 120_000, vehicle("train", 1, 120_000, true))); return m;
    }
    @Test public void associatesExactIdAfterUniqueCoMovementAtTwoLocations() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("train"));
        assertNull(observe(m, l, 0, 0, vehicle("train", 0, 0, true)));
        TransitLine.Departure selected = observe(m, l, 1, 120_000, vehicle("train", 1, 120_000, true));
        assertEquals("train", selected.trip.id); assertEquals("20261118", selected.date);
    }
    @Test public void canAssociateUsingReportedStoppedStationsWithoutTrainCoordinates() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("train"));
        assertNull(observe(m, l, 0, 0, vehicle("train", 0, 0, false)));
        assertNotNull(observe(m, l, 1, 120_000, vehicle("train", 1, 120_000, false)));
    }
    @Test public void resolvesStopSequenceThroughTheMatchingStaticTrip() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("train"));
        VehiclePosition a = vehicle("train", 0, 0, false).toBuilder().clearStopId().setCurrentStopSequence(10).build();
        VehiclePosition b = vehicle("train", 1, 120_000, false).toBuilder().clearStopId().setCurrentStopSequence(11).build();
        assertNull(observe(m, l, 0, 0, a)); assertNotNull(observe(m, l, 1, 120_000, b));
    }
    @Test public void neverChoosesBetweenTwoTrainsSharingTheSamePlatform() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("one"), trip("two"));
        assertNull(observe(m, l, 0, 0, vehicle("one", 0, 0, true), vehicle("two", 0, 0, true)));
        assertNull(observe(m, l, 1, 120_000, vehicle("one", 1, 120_000, true), vehicle("two", 1, 120_000, true)));
    }
    @Test public void aRepeatedVehicleTimestampCannotConfirmIdentity() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("train"));
        observe(m, l, 0, 0, vehicle("train", 0, 0, true));
        assertNull(observe(m, l, 1, 15_000, vehicle("train", 1, 0, true)));
    }
    @Test public void standingAtOneStationNeverConfirmsIdentityDespiteFreshUpdates() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("train"));
        observe(m, l, 0, 0, vehicle("train", 0, 0, true));
        assertNull(observe(m, l, 0, 30_000, vehicle("train", 0, 30_000, true)));
    }
    @Test public void rejectsWrongRouteServiceDayDirectionPartialAndFrequencyTemplate() throws Exception {
        List<TransitLine.ScheduledTrip> invalid = Arrays.asList(trip("train", "Other-city-C5", Arrays.asList("A", "B", "C"), false),
                trip("train", "Madrid-C5", Arrays.asList("C", "B", "A"), false), trip("train", "Madrid-C5", Arrays.asList("A", "B"), false),
                trip("train", "Madrid-C5", Arrays.asList("A", "B", "C"), true));
        for (TransitLine.ScheduledTrip bad : invalid) {
            AutoTrainMatcher m = new AutoTrainMatcher(); assertNull(observe(m, line(bad), 0, 0, vehicle("train", 0, 0, true)));
            assertNull(observe(m, line(bad), 1, 120_000, vehicle("train", 1, 120_000, true)));
        }
        AutoTrainMatcher m = new AutoTrainMatcher();
        VehiclePosition wrong = vehicle("train", 0, 0, true).toBuilder().setTrip(vehicle("train", 0, 0, true).getTrip().toBuilder().setStartDate("20261117")).build();
        assertNull(observe(m, line(trip("train")), 0, 0, wrong));
        assertNull(observe(m, line(trip("train")), 1, 120_000, vehicle("train", 1, 120_000, true)));
    }
    @Test public void arrivalTargetWithoutCoordinatesIsNotEvidenceOfBeingAtThatStation() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("train"));
        observe(m, l, 0, 0, vehicle("train", 0, 0, false));
        assertNull(observe(m, l, 1, 120_000, vehicle("train", 1, 120_000, false).toBuilder().setCurrentStatus(VehiclePosition.VehicleStopStatus.IN_TRANSIT_TO).build()));
    }
    @Test public void staleGpsAndFreshHeaderWithOldVehicleCannotCreateAssociation() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("train"));
        observe(m, l, 0, 0, vehicle("train", 0, 0, true));
        assertNull(m.match(l, route, feed(120_000, vehicle("train", 1, 120_000, true)), gps(1, 100_000), now + 120_000, 220_000));
        assertNull(observe(m, l, 1, 120_000, vehicle("train", 1, 40_000, true)));
    }
    @Test public void serviceDayCanBeResolvedWhenDescriptorOmitsIt() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("train"));
        VehiclePosition a = vehicle("train", 0, 0, true), b = vehicle("train", 1, 120_000, true);
        assertNull(observe(m, l, 0, 0, a.toBuilder().setTrip(a.getTrip().toBuilder().clearStartDate()).build()));
        assertEquals("20261118", observe(m, l, 1, 120_000, b.toBuilder().setTrip(b.getTrip().toBuilder().clearStartDate()).build()).date);
    }
    @Test public void signalLossRetainsPreviouslyMatchedTrainForTunnelUpdates() throws Exception {
        AutoTrainMatcher m = associated();
        assertEquals("train", m.match(line(trip("train")), route, feed(140_000, vehicle("train", 1, 140_000, true)), null, now + 140_000, 240_000).trip.id);
    }
    @Test public void contradictoryGpsReleasesTheWrongAssociationImmediately() throws Exception {
        AutoTrainMatcher m = associated();
        assertNull(observe(m, line(trip("train")), 0, 150_000, vehicle("train", 2, 150_000, true)));
        assertNull(m.match(line(trip("train")), route, feed(160_000), null, now + 160_000, 260_000));
    }
    @Test public void unknownRealtimeIdIsNeverGuessedFromScheduleOrName() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("train"));
        observe(m, l, 0, 0, vehicle("unknown", 0, 0, true));
        assertNull(observe(m, l, 1, 120_000, vehicle("unknown", 1, 120_000, true)));
    }
    @Test public void selectedTripFeedsTheExistingPredictionAndIncidentIdJoin() throws Exception {
        AutoTrainMatcher m = new AutoTrainMatcher(); TransitLine l = line(trip("train"));
        observe(m, l, 0, 0, vehicle("train", 0, 0, true));
        TransitLine.Departure selected = observe(m, l, 1, 120_000, vehicle("train", 1, 120_000, true));
        RealtimeContext context = new RealtimeContext(null, "", "B", "C", l.routeIds, l.agencyIds, new HashSet<>(Arrays.asList("A", "B", "C"))).withTrip(selected);
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository(); repo.positions.vehicles.put("train", vehicle("train", 1, 120_000, true)); repo.positions.timestamp = now + 120_000;
        repo.updates.timestamp = now + 120_000; repo.updates.updateTimes.put("train", now + 120_000);
        repo.updates.updates.put("train", TripUpdate.newBuilder().setTrip(vehicle("train", 1, 120_000, true).getTrip())
                .addStopTimeUpdate(TripUpdate.StopTimeUpdate.newBuilder().setStopId("B")
                        .setArrival(TripUpdate.StopTimeEvent.newBuilder().setTime((now + 180_000) / 1000))).build());
        repo.alerts.timestamp = now + 120_000;
        repo.alerts.alerts.put("issue", Alert.newBuilder().setEffect(Alert.Effect.NO_SERVICE)
                .addInformedEntity(EntitySelector.newBuilder().setTrip(vehicle("train", 1, 120_000, true).getTrip()))
                .setHeaderText(TranslatedString.newBuilder().addTranslation(TranslatedString.Translation.newBuilder().setText("Obras"))).build());
        RealtimeSnapshot result = repo.interpret(context, now + 120_000);
        assertTrue(result.hasFreshVehicle); assertEquals("B", result.stopId); assertEquals(now + 180_000, result.predictionAt);
        assertTrue(result.alerts.contains("Obras")); assertFalse(result.critical.isEmpty());
    }
}
