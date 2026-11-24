package es.yori.sleeptrain.data.realtime;

import com.google.transit.realtime.GtfsRealtime.*;
import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.core.StationMatcher;
import es.yori.sleeptrain.domain.TransitLine;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Associates only exact local trip IDs, with unique, independently updated evidence of co-movement. */
public final class AutoTrainMatcher {
    public static final class DeviceFix {
        public final double latitude, longitude, position;
        public final float accuracy;
        public final int stationIndex;
        public final long elapsed, wall;
        public DeviceFix(double latitude, double longitude, float accuracy, double position, int stationIndex, long elapsed, long wall) {
            this.latitude = latitude; this.longitude = longitude; this.accuracy = accuracy;
            this.position = position; this.stationIndex = stationIndex; this.elapsed = elapsed; this.wall = wall;
        }
        boolean fresh(long nowWall, long nowElapsed) {
            return Double.isFinite(latitude) && Double.isFinite(longitude) && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180
                    && Double.isFinite(position) && Float.isFinite(accuracy) && accuracy > 0 && accuracy <= 80
                    && nowElapsed >= elapsed && nowElapsed - elapsed <= StationMatcher.MAX_AGE_MS
                    && nowWall >= wall && nowWall - wall <= StationMatcher.MAX_AGE_MS;
        }
    }
    private TransitLine.Departure selected, pending;
    private DeviceFix firstFix;
    private long firstVehicleTime;
    public AutoTrainMatcher() { }
    public AutoTrainMatcher(TransitLine.Departure previous) { selected = previous; }

    public TransitLine.Departure match(TransitLine line, List<Station> route, FeedIndex feed, DeviceFix gps, long nowWall, long nowElapsed) {
        if (line == null || !line.realtimeAvailable || route.size() < 2) return clear();
        if (gps == null || !gps.fresh(nowWall, nowElapsed) || !FeedIndex.fresh(feed.timestamp, nowWall)) return selected;
        List<TransitLine.Departure> matches = new ArrayList<>();
        for (VehiclePosition vehicle : feed.vehicles.values()) {
            TransitLine.Departure trip = eligible(line, route, vehicle, nowWall);
            if (trip == null) continue;
            double distance = distance(route, trip.trip, vehicle, gps, nowWall);
            if (Double.isFinite(distance) && distance <= radius(vehicle, gps)) matches.add(trip);
            // Fresh, clearly contradictory device evidence invalidates the old association immediately.
            if (same(selected, trip) && distance > 600) selected = null;
        }
        if (selected != null) return selected;
        if (matches.size() != 1) { pending = null; firstFix = null; return null; }
        TransitLine.Departure candidate = matches.get(0);
        VehiclePosition vehicle = feed.vehicles.get(candidate.trip.id);
        long vehicleTime = vehicle.getTimestamp() * 1000;
        if (!same(pending, candidate) || firstFix == null || gps.elapsed - firstFix.elapsed > 600_000 || gps.position < firstFix.position - .1) {
            pending = candidate; firstFix = gps; firstVehicleTime = vehicleTime; return null;
        }
        // Repeated headers, GPS while standing still and a second train at the same platform are not identification.
        if (gps.elapsed - firstFix.elapsed >= 10_000 && vehicleTime > firstVehicleTime
                && gps.position - firstFix.position >= .15
                && StationMatcher.distanceMeters(firstFix.latitude, firstFix.longitude, gps.latitude, gps.longitude) >= 150) {
            selected = candidate; pending = null; firstFix = null;
        }
        return selected;
    }
    public void reset() { clear(); }
    private TransitLine.Departure clear() { selected = null; pending = null; firstFix = null; return null; }
    private static boolean same(TransitLine.Departure a, TransitLine.Departure b) {
        return a != null && b != null && a.trip.id.equals(b.trip.id) && a.date.equals(b.date);
    }
    private static TransitLine.Departure eligible(TransitLine line, List<Station> route, VehiclePosition v, long now) {
        if (!v.hasTrip() || !v.hasTimestamp() || !FeedIndex.fresh(v.getTimestamp() * 1000, now)) return null;
        TripDescriptor descriptor = v.getTrip();
        TransitLine.ScheduledTrip trip = line.trips.get(descriptor.getTripId());
        if (trip == null || trip.frequencyBased || !line.routeIds.contains(trip.routeId)
                || descriptor.hasRouteId() && !trip.routeId.equals(descriptor.getRouteId())
                || descriptor.getScheduleRelationship() == TripDescriptor.ScheduleRelationship.CANCELED
                || descriptor.getScheduleRelationship() == TripDescriptor.ScheduleRelationship.DELETED) return null;
        Integer from = trip.indexes.get(route.get(0).id);
        Integer to = trip.indexes.get(route.get(route.size() - 1).id);
        if (from == null || to == null || from >= to) return null;
        // A partial train, a wrong direction or a different stop sequence cannot cover this leg.
        int previous = from - 1;
        for (Station station : route) {
            Integer at = trip.indexes.get(station.id);
            if (at == null || at <= previous) return null;
            previous = at;
        }
        if (descriptor.hasStartDate()) return dated(trip, route, descriptor.getStartDate(), now);
        LocalDate today = Instant.ofEpochMilli(now).atZone(TransitLine.ZONE).toLocalDate();
        TransitLine.Departure found = null;
        for (LocalDate date : Arrays.asList(today.minusDays(1), today)) {
            TransitLine.Departure match = dated(trip, route, date.format(DateTimeFormatter.BASIC_ISO_DATE), now);
            if (match != null) { if (found != null) return null; found = match; }
        }
        return found;
    }
    private static TransitLine.Departure dated(TransitLine.ScheduledTrip trip, List<Station> route, String date, long now) {
        if (!trip.dates.contains(date)) return null;
        long start = trip.timeAt(trip.stopIds.get(0), date), end = trip.timeAt(trip.stopIds.get(trip.stopIds.size() - 1), date);
        // This is a service-day sanity check, never the evidence used to choose a train.
        if (now < start - 3_600_000 || now > end + 6 * 3_600_000L) return null;
        return new TransitLine.Departure(trip, date, trip.timeAt(route.get(0).id, date));
    }
    private static double radius(VehiclePosition v, DeviceFix gps) {
        return Math.min(350, 100 + gps.accuracy * 2 + Math.abs(v.getTimestamp() * 1000 - gps.wall) * .015);
    }
    private static double distance(List<Station> route, TransitLine.ScheduledTrip trip, VehiclePosition v, DeviceFix gps, long now) {
        if (Math.abs(v.getTimestamp() * 1000 - gps.wall) > 20_000 || now - v.getTimestamp() * 1000 > 30_000) return Double.NaN;
        if (v.hasPosition()) {
            Position p = v.getPosition();
            if (!Float.isFinite(p.getLatitude()) || !Float.isFinite(p.getLongitude())
                    || Math.abs(p.getLatitude()) > 90 || Math.abs(p.getLongitude()) > 180) return Double.NaN;
            return StationMatcher.distanceMeters(gps.latitude, gps.longitude, p.getLatitude(), p.getLongitude());
        }
        // Without train coordinates only STOPPED_AT is actual station evidence; IN_TRANSIT_TO is not an arrival.
        String stop = v.hasStopId() ? v.getStopId() : v.hasCurrentStopSequence() ? trip.stopForSequence(v.getCurrentStopSequence()) : "";
        for (int i = 0; i < route.size(); i++) {
            if (!route.get(i).id.equals(stop)) continue;
            if (v.hasCurrentStatus() && v.getCurrentStatus() == VehiclePosition.VehicleStopStatus.STOPPED_AT) {
                if (gps.stationIndex != i) return Math.abs(gps.position - i) > 1.1 ? Double.POSITIVE_INFINITY : Double.NaN;
                Station s = route.get(i);
                double distance = StationMatcher.distanceMeters(gps.latitude, gps.longitude, s.latitude, s.longitude);
                return distance <= 110 ? distance : Double.NaN;
            }
            return Double.NaN;
        }
        return Double.NaN;
    }
}
