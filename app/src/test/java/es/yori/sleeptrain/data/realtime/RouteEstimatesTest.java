package es.yori.sleeptrain.data.realtime;

import com.google.transit.realtime.GtfsRealtime.*;
import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.domain.JourneyPlan;
import es.yori.sleeptrain.domain.TransitLine;
import org.junit.Test;
import java.time.LocalDate;
import java.util.*;
import static es.yori.sleeptrain.data.realtime.RouteEstimates.Status.*;
import static org.junit.Assert.*;

public class RouteEstimatesTest {
    private final String today = "20261123";
    private final long now = LocalDate.of(2026, 11, 23).atTime(12, 0).atZone(TransitLine.ZONE).toInstant().toEpochMilli();
    private final RenfeRealtimeRepository feed = new RenfeRealtimeRepository();
    private final Station a = new Station("a", "A", 40, -4);
    private final Station b = new Station("b", "B", 40.01, -4);
    private final Station c = new Station("c", "C", 40.02, -4);
    private final Station d = new Station("d", "D", 40.03, -4);

    @Test public void aLiveDelayChangesTheRankingWithoutDiscardingTheChosenItinerary() throws Exception {
        JourneyPlan rail = direct(line("RENFE_R", a, b, c, trip("first", 120, "a", "b", "c")), "a", "c");
        JourneyPlan metro = direct(line("METRO_S", a, b, c), "a", "c");
        RouteEstimates estimates = new RouteEstimates(Collections.emptyMap(), feed, now);
        assertEquals(SCHEDULE, estimates.atDeparture(rail).status());
        assertEquals(720, estimates.atDeparture(rail).seconds());

        setUpdate(now, update("first").addStopTimeUpdate(TripUpdate.StopTimeUpdate.newBuilder()
                .setStopSequence(11).setArrival(TripUpdate.StopTimeEvent.newBuilder().setTime((now + 25 * 60_000) / 1000))));
        RouteEstimates.Option updated = estimates.atDeparture(rail);
        assertEquals(LIVE, updated.status());
        assertEquals(30 * 60, updated.seconds());
        List<RouteEstimates.Option> choices = RouteEstimates.choices(Arrays.asList(updated, estimates.atDeparture(metro)), rail.routeKey());
        assertEquals(metro, choices.get(0).plan());
        assertTrue(choices.contains(updated));
    }

    @Test public void filteringRequiresEveryLegToUseTheSelectedOperatorAndOrdersAreIndependent() {
        TransitLine metro = line("METRO_S", a, b, c);
        TransitLine rail = line("RENFE_R", b, c, d);
        RouteEstimates.Option onlyMetro = new RouteEstimates.Option(direct(metro, "a", "c"), 900, ESTIMATE);
        RouteEstimates.Option onlyRail = new RouteEstimates.Option(direct(rail, "b", "d"), 800, ESTIMATE);
        JourneyPlan mixed = new JourneyPlan(Arrays.asList(new JourneyPlan.Leg(metro, "a", "b", 1),
                new JourneyPlan.Leg(rail, "b", "d", 1)), "B", 300);
        RouteEstimates.Option fasterWithChange = new RouteEstimates.Option(mixed, 750, LIVE);
        List<RouteEstimates.Option> candidates = Arrays.asList(onlyMetro, fasterWithChange, onlyRail);
        assertEquals(Collections.singletonList(onlyMetro), RouteEstimates.filter(candidates, RouteEstimates.Operator.METRO));
        assertEquals(Collections.singletonList(onlyRail), RouteEstimates.filter(candidates, RouteEstimates.Operator.RENFE));
        assertEquals(Arrays.asList(fasterWithChange, onlyRail, onlyMetro), RouteEstimates.ranked(candidates, RouteEstimates.Order.TIME));
        assertEquals(Arrays.asList(onlyRail, onlyMetro, fasterWithChange), RouteEstimates.ranked(candidates, RouteEstimates.Order.TRANSFERS));
    }

    @Test public void missesAnEarlyConnectionAndIgnoresTheCanceledNextTrain() throws Exception {
        TransitLine metro = line("METRO_S", a, b, c);
        TransitLine rail = line("RENFE_R", b, c, d, trip("early", 480, "b", "c", "d"),
                trip("cancelled", 720, "b", "c", "d"), trip("later", 1_200, "b", "c", "d"));
        JourneyPlan plan = new JourneyPlan(Arrays.asList(new JourneyPlan.Leg(metro, "a", "b", 1),
                new JourneyPlan.Leg(rail, "b", "d", 1)), "B", 300);
        RouteEstimates estimates = new RouteEstimates(Collections.emptyMap(), feed, now);
        assertEquals(1320, estimates.atDeparture(plan).seconds());
        setUpdate(now, update("cancelled").setTrip(descriptor("cancelled", today).setScheduleRelationship(TripDescriptor.ScheduleRelationship.CANCELED)));
        assertEquals(1800, estimates.atDeparture(plan).seconds());
    }

    @Test public void aSkippedAlarmStopAndAScopedInterruptionAffectTheRoute() throws Exception {
        JourneyPlan rail = direct(line("RENFE_R", a, b, c, trip("first", 120, "a", "b", "c")), "a", "c");
        RouteEstimates estimates = new RouteEstimates(Collections.emptyMap(), feed, now);
        setUpdate(now, update("first").addStopTimeUpdate(TripUpdate.StopTimeUpdate.newBuilder().setStopId("b")
                .setScheduleRelationship(TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED)));
        assertEquals(DISRUPTION, estimates.atDeparture(rail).status());
        feed.updates.accept(snapshot(now).build().toByteArray(), now);
        Alert.Builder alert = Alert.newBuilder().setEffect(Alert.Effect.NO_SERVICE);
        setAlert(alert.addInformedEntity(EntitySelector.newBuilder().setRouteId("other").setStopId("c")));
        assertEquals(SCHEDULE, estimates.atDeparture(rail).status());
        setAlert(alert.clearInformedEntity().addInformedEntity(EntitySelector.newBuilder().setRouteId("route").setStopId("c")));
        assertEquals(DISRUPTION, estimates.atDeparture(rail).status());
    }

    @Test public void staleOrWrongDateUpdatesDoNotSuppressAValidTrain() throws Exception {
        JourneyPlan rail = direct(line("RENFE_R", a, b, c, trip("first", 120, "a", "b", "c")), "a", "c");
        setUpdate(now, update("first").setTimestamp((now - 61_000) / 1000).setDelay(600));
        RouteEstimates estimates = new RouteEstimates(Collections.emptyMap(), feed, now);
        assertEquals(SCHEDULE, estimates.atDeparture(rail).status());
        setUpdate(now, update("first").setTrip(descriptor("first", "20261122")).setDelay(600));
        assertEquals(720, estimates.atDeparture(rail).seconds());
        RouteEstimates expired = new RouteEstimates(Collections.emptyMap(), feed, now + 8 * 86_400_000L);
        assertEquals(ESTIMATE, expired.atDeparture(rail).status());
    }

    private TransitLine line(String id, Station start, Station middle, Station end, TransitLine.ScheduledTrip... trips) {
        return new TransitLine(id, id, Arrays.asList(start, middle, end), new int[]{300, 300}, Arrays.asList(trips),
                Collections.singleton("route"), Collections.singleton("Renfe"), "", "", today, "");
    }

    private JourneyPlan direct(TransitLine line, String from, String to) {
        return new JourneyPlan(Collections.singletonList(new JourneyPlan.Leg(line, from, to, 1)), "", 0);
    }

    private TransitLine.ScheduledTrip trip(String id, int offset, String... stops) {
        int departure = 12 * 3600 + offset;
        return new TransitLine.ScheduledTrip(id, "route", Arrays.asList(stops), new int[]{departure, departure + 300, departure + 600},
                new int[]{10, 11, 12}, Collections.singleton(today), false);
    }

    private TripDescriptor.Builder descriptor(String id, String date) {
        return TripDescriptor.newBuilder().setTripId(id).setRouteId("route").setStartDate(date);
    }

    private TripUpdate.Builder update(String id) {
        return TripUpdate.newBuilder().setTrip(descriptor(id, today)).setTimestamp(now / 1000);
    }

    private FeedMessage.Builder snapshot(long time) {
        return FeedMessage.newBuilder().setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0").setTimestamp(time / 1000));
    }

    private void setUpdate(long time, TripUpdate.Builder update) throws Exception {
        feed.updates.accept(snapshot(time).addEntity(FeedEntity.newBuilder().setId("update").setTripUpdate(update)).build().toByteArray(), now);
    }

    private void setAlert(Alert.Builder alert) throws Exception {
        feed.alerts.accept(snapshot(now).addEntity(FeedEntity.newBuilder().setId("alert").setAlert(alert)).build().toByteArray(), now);
    }
}
