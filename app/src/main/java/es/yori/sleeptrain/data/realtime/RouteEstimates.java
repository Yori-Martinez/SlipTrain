package es.yori.sleeptrain.data.realtime;

import com.google.transit.realtime.GtfsRealtime.Alert;
import com.google.transit.realtime.GtfsRealtime.EntitySelector;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.domain.JourneyPlan;
import es.yori.sleeptrain.domain.TransitLine;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class RouteEstimates {
    private static final long SEARCH_WINDOW = 2 * 3_600_000L;
    private static final long LIVE_WINDOW = 30 * 60_000L;

    public record Option(JourneyPlan plan, int seconds, Status status) {
        public boolean unavailable() { return status == Status.NO_DEPARTURE || status == Status.DISRUPTION; }
    }

    public enum Status { ESTIMATE, SCHEDULE, LIVE, NO_DEPARTURE, DISRUPTION }
    public enum Operator { ALL, METRO, RENFE }
    public enum Order { RECOMMENDED, TIME, TRANSFERS }

    private final RenfeRealtimeRepository realtime;
    private final long now;
    private final Map<String, Long> serviceDays = new LinkedHashMap<>();
    private final String today;
    private final Map<LegKey, Timetable> timetables = new HashMap<>();
    private final Map<String, TransitLine> schedules;

    public RouteEstimates(Map<String, TransitLine> schedules, RenfeRealtimeRepository realtime, long now) {
        this.schedules = schedules;
        this.realtime = realtime;
        this.now = now;
        LocalDate date = Instant.ofEpochMilli(now).atZone(TransitLine.ZONE).toLocalDate();
        today = date.format(DateTimeFormatter.BASIC_ISO_DATE);
        for (LocalDate day : Arrays.asList(date.minusDays(1), date, date.plusDays(1))) {
            serviceDays.put(day.format(DateTimeFormatter.BASIC_ISO_DATE),
                    day.atTime(12, 0).atZone(TransitLine.ZONE).minusHours(12).toInstant().toEpochMilli());
        }
    }

    public static Option estimate(JourneyPlan plan) {
        return new Option(plan, plan.nominalSeconds(), Status.ESTIMATE);
    }

    /** Prefer useful alternatives with different lines when their arrival times are close. */
    public static List<Option> choices(List<Option> candidates, String selectedKey) {
        return choices(candidates, selectedKey, Order.RECOMMENDED);
    }

    public static List<Option> choices(List<Option> candidates, String selectedKey, Order order) {
        if (candidates.isEmpty()) return Collections.emptyList();
        return selectRecommendations(selectedKey, ranked(candidates, order), order == Order.RECOMMENDED);
    }

    public static List<Option> filter(List<Option> candidates, Operator operator) {
        if (operator == Operator.ALL) return candidates;
        List<Option> matches = new ArrayList<>();
        for (Option option : candidates) {
            if (option.plan().legs.stream().allMatch(leg -> leg.line.realtimeAvailable == (operator == Operator.RENFE))) matches.add(option);
        }
        return matches;
    }

    public static List<Option> ranked(List<Option> candidates, Order order) {
        Comparator<Option> time = Comparator.comparingInt(Option::seconds);
        Comparator<Option> transfers = Comparator.comparingInt(option -> option.plan().legs.size());
        Comparator<Option> ranking = switch (order) {
            case TIME -> time.thenComparing(transfers);
            case TRANSFERS -> transfers.thenComparing(time);
            case RECOMMENDED -> Comparator.<Option>comparingInt(option -> option.seconds() + 60 * (option.plan().legs.size() - 1))
                    .thenComparing(transfers);
        };
        List<Option> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparing(Option::unavailable).thenComparing(ranking).thenComparing(option -> option.plan().routeKey()));
        return sorted;
    }

    private static List<Option> selectRecommendations(String selectedKey, List<Option> sorted, boolean diversify) {
        List<Option> shown = new ArrayList<>();
        Set<List<String>> corridors = new HashSet<>();
        for (Option option : sorted) {
            if (!diversify) break;
            if (option.unavailable() != sorted.get(0).unavailable() || option.seconds() > sorted.get(0).seconds() + 8 * 60) continue;
            List<String> lines = option.plan().legs.stream().map(leg -> leg.line.name).collect(java.util.stream.Collectors.toList());
            if (corridors.add(lines)) shown.add(option);
            if (shown.size() == 3) break;
        }
        for (Option option : sorted) {
            if (shown.size() == 3) break;
            if (!shown.contains(option)) shown.add(option);
        }
        if (!selectedKey.isEmpty() && shown.stream().noneMatch(option -> option.plan().routeKey().equals(selectedKey))) {
            sorted.stream().filter(option -> option.plan().routeKey().equals(selectedKey)).findFirst().ifPresent(saved -> {
                if (shown.size() == 3) shown.remove(2);
                shown.add(saved);
            });
        }
        shown.sort(Comparator.comparingInt(sorted::indexOf));
        return shown;
    }

    public Option atDeparture(JourneyPlan plan) {
        long ready = now;
        Status status = Status.ESTIMATE;
        long changeTime = plan.legs.size() > 1 ? plan.walkingSeconds * 1000L / (plan.legs.size() - 1) : 0;
        for (int i = 0; i < plan.legs.size(); i++) {
            JourneyPlan.Leg leg = plan.legs.get(i);
            String alert = i == plan.legs.size() - 1 ? leg.alertStation().id : leg.destination;
            Ride ride = leg.line.realtimeAvailable ? nextRide(leg, alert, ready) : Ride.unknown();
            if (ride.status == Status.NO_DEPARTURE || ride.status == Status.DISRUPTION) {
                return new Option(plan, plan.nominalSeconds(), ride.status);
            }
            if (ride.status == Status.ESTIMATE) {
                for (int seconds : leg.segments) ready += seconds * 1000L;
            } else {
                ready = ride.arrival;
                if (status != Status.LIVE) status = ride.status;
            }
            ready += changeTime;
        }
        return new Option(plan, (int) ((ready - now - changeTime + 999) / 1000), status);
    }

    private Ride nextRide(JourneyPlan.Leg leg, String alert, long ready) {
        TransitLine line = schedules.getOrDefault(leg.line.id, leg.line);
        LegKey key = new LegKey(line.id, leg.origin, leg.destination, alert);
        Timetable table = timetables.computeIfAbsent(key, ignored -> timetable(line, leg, alert));
        Ride best = Ride.unknown();
        boolean disrupted = false;
        for (ScheduledRide ride : table.rides) {
            if (ride.departure < ready - SEARCH_WINDOW || ride.departure > ready + SEARCH_WINDOW) continue;
            TripUpdate update = matchedUpdate(ride);
            if (update != null
                    && (update.getTrip().getScheduleRelationship() == TripDescriptor.ScheduleRelationship.CANCELED
                    || update.getTrip().getScheduleRelationship() == TripDescriptor.ScheduleRelationship.DELETED)) {
                disrupted |= ride.departure >= ready;
                continue;
            }
            long departure = ride.departure, arrival = ride.arrival;
            boolean predicted = false;
            if (update != null) {
                Prediction prediction = predict(update, ride, alert);
                if (prediction.skipped) {
                    disrupted |= ride.departure >= ready;
                    continue;
                }
                departure = prediction.departure;
                arrival = prediction.arrival;
                predicted = prediction.measured;
            }
            if (departure < ready || departure > ready + SEARCH_WINDOW || arrival <= departure
                    || arrival - departure > 6 * 3_600_000L) continue;
            if (interrupted(leg, ride, departure)) {
                disrupted = true;
                continue;
            }
            if (best.status == Status.ESTIMATE || arrival < best.arrival) {
                best = new Ride(arrival, predicted ? Status.LIVE : Status.SCHEDULE);
            }
        }
        if (best.status != Status.ESTIMATE) return best;
        if (disrupted || interrupted(leg, null, ready)) return new Ride(0, Status.DISRUPTION);
        return table.covered ? new Ride(0, Status.NO_DEPARTURE) : best;
    }

    private Timetable timetable(TransitLine line, JourneyPlan.Leg leg, String alert) {
        List<ScheduledRide> result = new ArrayList<>();
        boolean covered = false;
        for (TransitLine.ScheduledTrip trip : line.trips.values()) {
            Integer from = trip.indexes.get(leg.origin), to = trip.indexes.get(leg.destination);
            if (trip.frequencyBased || from == null || to == null || from >= to || !line.routeIds.contains(trip.routeId)) continue;
            Integer alertIndex = trip.indexes.get(alert);
            if (alertIndex == null || alertIndex < from || alertIndex > to) continue;
            for (Map.Entry<String, Long> day : serviceDays.entrySet()) {
                if (!trip.dates.contains(day.getKey())) continue;
                long departure = day.getValue() + trip.seconds[from] * 1000L;
                long arrival = day.getValue() + trip.seconds[to] * 1000L;
                if (day.getKey().equals(today) || departure >= now && departure <= now + SEARCH_WINDOW) covered = true;
                result.add(new ScheduledRide(trip, day.getKey(), day.getValue(), from, to, departure, arrival));
            }
        }
        return new Timetable(result, covered);
    }

    private TripUpdate matchedUpdate(ScheduledRide ride) {
        long time = realtime.updates.updateTimes.getOrDefault(ride.trip.id, 0L);
        if (!FeedIndex.fresh(realtime.updates.timestamp, now) || !FeedIndex.fresh(time, now)) return null;
        TripUpdate update = realtime.updates.updates.get(ride.trip.id);
        if (update == null) return null;
        TripDescriptor trip = update.getTrip();
        if (trip.hasRouteId() && !trip.getRouteId().equals(ride.trip.routeId)) return null;
        if (trip.hasStartDate()) return trip.getStartDate().equals(ride.date) ? update : null;
        long first = ride.serviceStart + ride.trip.seconds[0] * 1000L;
        long last = ride.serviceStart + ride.trip.seconds[ride.trip.seconds.length - 1] * 1000L;
        return first <= now + LIVE_WINDOW && last >= now - LIVE_WINDOW ? update : null;
    }

    private static Prediction predict(TripUpdate update, ScheduledRide ride, String alertStop) {
        Long delay = update.hasDelay() ? update.getDelay() * 1000L : null;
        long departure = ride.departure, arrival = ride.arrival;
        boolean measured = false;
        int cursor = 0;
        List<StopTimeUpdate> updates = update.getStopTimeUpdateList();
        for (int i = 0; i <= ride.to; i++) {
            StopTimeUpdate stop = null;
            while (cursor < updates.size()) {
                StopTimeUpdate entry = updates.get(cursor);
                String id = entry.hasStopSequence() ? ride.trip.stopForSequence(entry.getStopSequence()) : entry.getStopId();
                Integer index = ride.trip.indexes.get(id);
                if (index != null && index > i) break;
                cursor++;
                if (index != null && index == i) { stop = entry; break; }
            }
            long scheduled = ride.serviceStart + ride.trip.seconds[i] * 1000L;
            Long arrivalDelay = delay, departureDelay = delay;
            if (stop != null) {
                switch (stop.getScheduleRelationship()) {
                    case SKIPPED -> {
                        if (i == ride.from || i == ride.to || ride.trip.stopIds.get(i).equals(alertStop)) {
                            return new Prediction(0, 0, false, true);
                        }
                    }
                    case NO_DATA -> arrivalDelay = departureDelay = null;
                    default -> {
                        arrivalDelay = delayAt(stop.getArrival(), scheduled);
                        departureDelay = delayAt(stop.getDeparture(), scheduled);
                        if (arrivalDelay == null) arrivalDelay = departureDelay;
                        if (departureDelay == null) departureDelay = arrivalDelay;
                    }
                }
            }
            if (i == ride.from && departureDelay != null) {
                departure += departureDelay;
                measured = true;
            }
            if (i == ride.to && arrivalDelay != null) {
                arrival += arrivalDelay;
                measured = true;
            }
            delay = departureDelay;
        }
        return new Prediction(departure, arrival, measured, false);
    }

    private static Long delayAt(StopTimeEvent event, long scheduled) {
        if (event.hasTime()) return event.getTime() * 1000L - scheduled;
        if (event.hasDelay()) return event.getDelay() * 1000L;
        return null;
    }

    private boolean interrupted(JourneyPlan.Leg leg, ScheduledRide ride, long time) {
        if (!FeedIndex.fresh(realtime.alerts.timestamp, now)) return false;
        for (Alert alert : realtime.alerts.alerts.values()) {
            if (alert.getEffect() != Alert.Effect.NO_SERVICE || !RenfeRealtimeRepository.active(alert, time)) continue;
            for (EntitySelector selector : alert.getInformedEntityList()) {
                if (selector.hasAgencyId() && !leg.line.agencyIds.contains(selector.getAgencyId())) continue;
                if (selector.hasRouteType() && selector.getRouteType() != 2) continue;
                if (selector.hasDirectionId()) continue; // The local timetable has no GTFS direction_id.
                if (selector.hasTrip() && (ride == null || !matches(selector.getTrip(), ride))) continue;
                if (selector.hasRouteId() && (ride == null
                        ? !leg.line.routeIds.equals(Collections.singleton(selector.getRouteId()))
                        : !ride.trip.routeId.equals(selector.getRouteId()))) continue;
                if (selector.hasStopId() && !servesStop(leg, selector.getStopId())) continue;
                if (selector.hasAgencyId() || selector.hasRouteId() || selector.hasTrip() || selector.hasStopId()) return true;
            }
        }
        return false;
    }

    private static boolean servesStop(JourneyPlan.Leg leg, String stopId) {
        for (Station station : leg.stations) if (station.id.equals(stopId)) return true;
        return false;
    }

    private static boolean matches(TripDescriptor descriptor, ScheduledRide ride) {
        return descriptor.hasTripId() && descriptor.getTripId().equals(ride.trip.id)
                && (!descriptor.hasRouteId() || descriptor.getRouteId().equals(ride.trip.routeId))
                && (!descriptor.hasStartDate() || descriptor.getStartDate().equals(ride.date));
    }

    private record LegKey(String line, String origin, String destination, String alert) { }
    private record Timetable(List<ScheduledRide> rides, boolean covered) { }
    private record ScheduledRide(TransitLine.ScheduledTrip trip, String date, long serviceStart, int from, int to,
                                 long departure, long arrival) { }
    private record Prediction(long departure, long arrival, boolean measured, boolean skipped) { }
    private record Ride(long arrival, Status status) {
        static Ride unknown() { return new Ride(0, Status.ESTIMATE); }
    }
}
