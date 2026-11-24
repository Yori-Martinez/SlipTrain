package es.yori.sleeptrain.data.realtime;

import com.google.transit.realtime.GtfsRealtime.*;
import es.yori.sleeptrain.domain.*;
import org.json.JSONObject;
import org.junit.Test;
import java.time.*;
import java.util.*;
import static org.junit.Assert.*;

public class RealtimeTest {
    private final long now = LocalDate.of(2026, 11, 16).atTime(12, 0).atZone(TransitLine.ZONE).toInstant().toEpochMilli();
    private final TransitLine.ScheduledTrip trip = new TransitLine.ScheduledTrip("train", "C5-route", Arrays.asList("A", "B", "C"),
            new int[]{12 * 3600, 12 * 3600 + 600, 12 * 3600 + 900}, new int[]{10, 11, 12}, Collections.singleton("20261116"), false);
    private RealtimeContext context() { return new RealtimeContext(trip, "20261116", "B", "C", Collections.singleton("C5-route"), Collections.singleton("Renfe"), new HashSet<>(Arrays.asList("A", "B", "C"))); }
    private byte[] feed(long timestamp, boolean diff, FeedEntity... entities) {
        FeedMessage.Builder message = FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0")
                .setTimestamp(timestamp / 1000).setIncrementality(diff ? FeedHeader.Incrementality.DIFFERENTIAL : FeedHeader.Incrementality.FULL_DATASET));
        for (FeedEntity entity : entities) message.addEntity(entity); return message.build().toByteArray();
    }
    private TripDescriptor descriptor(String id) { return TripDescriptor.newBuilder().setTripId(id).setRouteId("C5-route").setStartDate("20261116").build(); }
    private FeedEntity vehicle(String id, long timestamp) {
        return FeedEntity.newBuilder().setId("vehicle").setVehicle(VehiclePosition.newBuilder().setTrip(descriptor(id)).setTimestamp(timestamp / 1000)
                .setCurrentStopSequence(11).setCurrentStatus(VehiclePosition.VehicleStopStatus.STOPPED_AT)).build();
    }
    @Test public void binaryDecodingAndLocalSequenceJoinNeedNoGps() throws Exception {
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository(); repo.positions.accept(feed(now, false, vehicle("train", now)), now);
        RealtimeSnapshot result = repo.interpret(context(), now); assertTrue(result.hasFreshVehicle); assertEquals("B", result.stopId);
    }
    @Test public void wrongTripAndWrongServiceDateNeverAdvance() throws Exception {
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository(); repo.positions.accept(feed(now, false, vehicle("other", now)), now);
        assertFalse(repo.interpret(context(), now).hasFreshVehicle);
        FeedEntity wrongDate = FeedEntity.newBuilder().setId("v").setVehicle(VehiclePosition.newBuilder().setTrip(descriptor("train").toBuilder().setStartDate("20261115"))
                .setStopId("B").setTimestamp(now / 1000)).build();
        repo.positions.accept(feed(now, false, wrongDate), now); assertFalse(repo.interpret(context(), now).hasFreshVehicle);
    }
    @Test public void freshHeaderDoesNotReviveStaleVehicle() throws Exception {
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository(); repo.positions.accept(feed(now, false, vehicle("train", now - 61_000)), now);
        assertFalse(repo.interpret(context(), now).hasFreshVehicle);
    }
    @Test public void staleFutureAndOutOfOrderHeadersAreRejected() throws Exception {
        FeedIndex index = new FeedIndex(); assertFalse(index.accept(feed(now - 61_000, false), now)); assertFalse(index.accept(feed(now + 11_000, false), now));
        assertTrue(index.accept(feed(now, false), now)); assertFalse(index.accept(feed(now - 1_000, false), now));
    }
    @Test public void fullSnapshotReplacesAndDifferentialDeletes() throws Exception {
        FeedIndex index = new FeedIndex(); index.accept(feed(now, false, vehicle("train", now)), now);
        index.accept(feed(now + 1000, true, FeedEntity.newBuilder().setId("vehicle").setIsDeleted(true).build()), now + 1000); assertTrue(index.vehicles.isEmpty());
        index.accept(feed(now + 2000, false, vehicle("train", now)), now + 2000);
        index.accept(feed(now + 3000, false), now + 3000); assertTrue(index.vehicles.isEmpty());
    }
    @Test public void predictionUsesStopTimeAndReportsDelay() throws Exception {
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository();
        FeedEntity update = FeedEntity.newBuilder().setId("u").setTripUpdate(TripUpdate.newBuilder().setTrip(descriptor("train")).setTimestamp(now / 1000)
                .addStopTimeUpdate(TripUpdate.StopTimeUpdate.newBuilder().setStopSequence(11).setArrival(TripUpdate.StopTimeEvent.newBuilder().setDelay(120).setTime((now + 720_000) / 1000)))).build();
        repo.updates.accept(feed(now, false, update), now); RealtimeSnapshot result = repo.interpret(context(), now);
        assertEquals(now + 720_000, result.predictionAt); assertEquals(Integer.valueOf(120), result.delaySeconds);
    }
    @Test public void tripDelayCanBeAppliedToMatchingStaticSchedule() throws Exception {
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository(); repo.updates.accept(feed(now, false, FeedEntity.newBuilder().setId("u")
                .setTripUpdate(TripUpdate.newBuilder().setTrip(descriptor("train")).setDelay(180)).build()), now);
        assertEquals(now + 780_000, repo.interpret(context(), now).predictionAt);
    }
    @Test public void unchangedDifferentialEntityDoesNotGetANewTimestamp() throws Exception {
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository(); repo.updates.accept(feed(now, false, FeedEntity.newBuilder().setId("u")
                .setTripUpdate(TripUpdate.newBuilder().setTrip(descriptor("train")).setDelay(180)).build()), now);
        repo.updates.accept(feed(now + 61_000, true), now + 61_000);
        assertEquals(0, repo.interpret(context(), now + 61_000).predictionAt);
    }
    @Test public void cancellationAndSkippedDestinationRaiseCriticalNotice() throws Exception {
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository(); repo.updates.accept(feed(now, false, FeedEntity.newBuilder().setId("u")
                .setTripUpdate(TripUpdate.newBuilder().setTrip(descriptor("train").toBuilder().setScheduleRelationship(TripDescriptor.ScheduleRelationship.CANCELED))).build()), now);
        assertTrue(repo.interpret(context(), now).critical.contains("cancelado"));
        repo.updates.accept(feed(now, false, FeedEntity.newBuilder().setId("u").setTripUpdate(TripUpdate.newBuilder().setTrip(descriptor("train"))
                .addStopTimeUpdate(TripUpdate.StopTimeUpdate.newBuilder().setStopId("C").setScheduleRelationship(TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED))).build()), now);
        assertTrue(repo.interpret(context(), now).critical.contains("suprimido"));
    }
    @Test public void noDataDoesNotManufactureZeroDelay() throws Exception {
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository(); repo.updates.accept(feed(now, false, FeedEntity.newBuilder().setId("u").setTripUpdate(TripUpdate.newBuilder().setTrip(descriptor("train")).setDelay(0)
                .addStopTimeUpdate(TripUpdate.StopTimeUpdate.newBuilder().setStopId("B").setScheduleRelationship(TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA))).build()), now);
        assertEquals(0, repo.interpret(context(), now).predictionAt);
    }
    @Test public void serviceAlertsMatchAllSelectorFieldsAndActivePeriod() throws Exception {
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository();
        Alert alert = Alert.newBuilder().setEffect(Alert.Effect.NO_SERVICE).addActivePeriod(TimeRange.newBuilder().setStart(now / 1000 - 10).setEnd(now / 1000 + 10))
                .addInformedEntity(EntitySelector.newBuilder().setRouteId("C5-route").setStopId("B"))
                .setHeaderText(TranslatedString.newBuilder().addTranslation(TranslatedString.Translation.newBuilder().setText("Corte en tu línea").setLanguage("es"))).build();
        repo.alerts.accept(feed(now, false, FeedEntity.newBuilder().setId("a").setAlert(alert).build()), now);
        assertEquals(1, repo.interpret(context(), now).alerts.size()); assertFalse(repo.interpret(context(), now).critical.isEmpty());
        assertTrue(repo.interpret(context(), now + 11_000).critical.isEmpty());
        repo.alerts.accept(feed(now, false, FeedEntity.newBuilder().setId("a").setAlert(alert.toBuilder().clearInformedEntity().addInformedEntity(EntitySelector.newBuilder().setRouteId("other").setStopId("B"))).build()), now);
        assertTrue(repo.interpret(context(), now).alerts.isEmpty());
    }
    @Test public void metroBoardsAreFreshDirectionSpecificAndNeverTrainPosition() throws Exception {
        String json = "{\"Vtelindicadores\":[{\"linea\":3,\"nombreest\":\"Embajadores\",\"sentido\":\"Moncloa\",\"anden\":1,\"proximo\":2,\"fechaHoraEmisionPrevision\":\"2026-11-16T12:00:00+01:00\"}]}";
        assertTrue(MetroArrivalsRepository.parse(new JSONObject(json), "par_4_46", "Moncloa", now).contains("2 min"));
        assertTrue(MetroArrivalsRepository.parse(new JSONObject(json), "par_4_46", "El Casar", now).startsWith("Sin"));
        assertTrue(MetroArrivalsRepository.parse(new JSONObject(json), "par_4_46", "Moncloa", now + 61_000).startsWith("Sin"));
        assertTrue(MetroArrivalsRepository.parse(new JSONObject(json.replace("\"linea\":3", "\"linea\":6")), "par_4_46", "Moncloa", now).startsWith("Sin"));
    }
}
