package es.yori.sleeptrain.data.realtime;

import com.google.transit.realtime.GtfsRealtime.*;
import es.yori.sleeptrain.data.HttpClient;
import es.yori.sleeptrain.domain.RealtimeSnapshot;
import java.util.*;

/** Native protobuf decoding and exact local ID joins; no proxy or database. */
public final class RenfeRealtimeRepository {
    public static final String POSITIONS = "https://gtfsrt.renfe.com/vehicle_positions.pb";
    public static final String UPDATES = "https://gtfsrt.renfe.com/trip_updates.pb";
    public static final String ALERTS = "https://gtfsrt.renfe.com/alerts.pb";
    public final FeedIndex positions = new FeedIndex(), updates = new FeedIndex(), alerts = new FeedIndex();
    public RealtimeSnapshot poll(RealtimeContext context) {
        refresh(); return interpret(context, System.currentTimeMillis());
    }
    public void refresh() {
        fetch(positions, POSITIONS, 6_000); fetch(updates, UPDATES, 6_000); fetch(alerts, ALERTS, 6_000);
    }
    public void refreshForPlanning() {
        long now = System.currentTimeMillis();
        if (now - updates.timestamp > 20_000) fetch(updates, UPDATES, 1_800);
        if (now - alerts.timestamp > 20_000) fetch(alerts, ALERTS, 1_800);
    }
    private void fetch(FeedIndex index, String url, int timeout) {
        try { index.accept(HttpClient.get(url, 8 * 1024 * 1024, timeout), System.currentTimeMillis()); }
        catch (Exception ignored) { /* Cached records retain their original timestamp and expire. */ }
    }
    public RealtimeSnapshot interpret(RealtimeContext context, long now) {
        RealtimeSnapshot result = new RealtimeSnapshot();
        if (context.trip != null) {
            VehiclePosition v = positions.vehicles.get(context.trip.id);
            if (FeedIndex.fresh(positions.timestamp, now) && v != null && matches(v.getTrip(), context)
                    && v.hasTimestamp() && FeedIndex.fresh(v.getTimestamp() * 1000, now)) {
                String stop = v.hasStopId() ? v.getStopId() : v.hasCurrentStopSequence() ? context.trip.stopForSequence(v.getCurrentStopSequence()) : "";
                if (context.stops.contains(stop)) {
                    result.stopId = stop; result.vehicleStatus = v.getCurrentStatus().name(); result.measuredAt = v.getTimestamp() * 1000;
                    result.hasFreshVehicle = true;
                }
            }
            TripUpdate update = updates.updates.get(context.trip.id);
            long measured = updates.updateTimes.getOrDefault(context.trip.id, 0L);
            if (FeedIndex.fresh(updates.timestamp, now) && update != null && matches(update.getTrip(), context) && FeedIndex.fresh(measured, now)) {
                if (update.getTrip().getScheduleRelationship() == TripDescriptor.ScheduleRelationship.CANCELED
                        || update.getTrip().getScheduleRelationship() == TripDescriptor.ScheduleRelationship.DELETED) result.critical = "Renfe ha cancelado o retirado el tren seleccionado";
                Integer inheritedDelay = update.hasDelay() ? update.getDelay() : null;
                long targetTime = -1; boolean targetNoData = false;
                Integer targetIndex = context.trip.indexes.get(context.alertStop);
                for (TripUpdate.StopTimeUpdate stopUpdate : update.getStopTimeUpdateList()) {
                    String stop = stopUpdate.hasStopId() ? stopUpdate.getStopId() : stopUpdate.hasStopSequence() ? context.trip.stopForSequence(stopUpdate.getStopSequence()) : "";
                    if (stopUpdate.getScheduleRelationship() == TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED
                            && (stop.equals(context.destination) || stop.equals(context.alertStop))) result.critical = "Renfe ha suprimido una parada de tu aviso o destino";
                    Integer index = context.trip.indexes.get(stop);
                    if (index == null || targetIndex == null || index > targetIndex) continue;
                    if (stopUpdate.getScheduleRelationship() == TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA) {
                        inheritedDelay = null; if (stop.equals(context.alertStop)) targetNoData = true; continue;
                    }
                    if (stopUpdate.hasArrival()) {
                        if (stopUpdate.getArrival().hasDelay()) inheritedDelay = stopUpdate.getArrival().getDelay();
                        else if (stopUpdate.getArrival().hasTime()) inheritedDelay = (int) ((stopUpdate.getArrival().getTime() * 1000 - context.trip.timeAt(stop, context.serviceDate)) / 1000);
                        if (stop.equals(context.alertStop) && stopUpdate.getArrival().hasTime()) targetTime = stopUpdate.getArrival().getTime() * 1000;
                    }
                    if (!stop.equals(context.alertStop) && stopUpdate.hasDeparture() && stopUpdate.getDeparture().hasDelay()) inheritedDelay = stopUpdate.getDeparture().getDelay();
                }
                result.delaySeconds = inheritedDelay;
                if (targetTime < 0 && inheritedDelay != null && !targetNoData) {
                    long scheduled = context.trip.timeAt(context.alertStop, context.serviceDate);
                    if (scheduled > 0) targetTime = scheduled + inheritedDelay * 1000L;
                }
                if (targetTime >= now - 60_000 && targetTime < now + 6 * 3600_000L && !targetNoData) {
                    result.predictionAt = targetTime; result.predictionExpiresAt = Math.min(measured, updates.timestamp) + 60_000;
                }
            }
        }
        if (FeedIndex.fresh(alerts.timestamp, now)) for (Alert alert : alerts.alerts.values()) {
            if (!active(alert, now)) continue;
            boolean relevant = false;
            for (EntitySelector selector : alert.getInformedEntityList()) relevant |= applies(selector, context);
            if (!relevant) continue;
            String text = translated(alert.getHeaderText());
            if (text.isEmpty()) text = translated(alert.getDescriptionText());
            if (!text.isEmpty() && result.alerts.size() < 5) result.alerts.add(text);
            if (alert.getEffect() == Alert.Effect.NO_SERVICE) result.critical = "Incidencia Renfe: " + (text.isEmpty() ? "servicio interrumpido en tu recorrido" : text);
        }
        return result;
    }
    private static boolean matches(TripDescriptor descriptor, RealtimeContext c) {
        return c.trip != null && c.trip.id.equals(descriptor.getTripId())
                && (!descriptor.hasStartDate() || descriptor.getStartDate().equals(c.serviceDate))
                && (!descriptor.hasRouteId() || descriptor.getRouteId().equals(c.trip.routeId));
    }
    private static boolean applies(EntitySelector s, RealtimeContext c) {
        boolean constrained = false;
        if (s.hasAgencyId()) { constrained = true; if (!c.agencies.contains(s.getAgencyId())) return false; }
        if (s.hasRouteId()) { constrained = true; if (c.trip != null ? !c.trip.routeId.equals(s.getRouteId()) : !c.routeIds.contains(s.getRouteId())) return false; }
        if (s.hasRouteType() && s.getRouteType() != 2) return false;
        if (s.hasTrip()) { constrained = true; if (!matches(s.getTrip(), c)) return false; }
        if (s.hasStopId()) { constrained = true; if (!c.stops.contains(s.getStopId())) return false; }
        return constrained;
    }
    static boolean active(Alert alert, long now) {
        if (alert.getActivePeriodCount() == 0) return true;
        for (TimeRange range : alert.getActivePeriodList()) if ((!range.hasStart() || now >= range.getStart() * 1000) && (!range.hasEnd() || now < range.getEnd() * 1000)) return true;
        return false;
    }
    private static String translated(TranslatedString value) {
        for (TranslatedString.Translation t : value.getTranslationList()) if (t.getLanguage().startsWith("es")) return t.getText();
        return value.getTranslationCount() > 0 ? value.getTranslation(0).getText() : "";
    }
}
