package es.yori.sleeptrain.domain;

import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.core.StationMatcher;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.*;
import java.util.function.Predicate;

/** Routes across the downloaded rail graph using travel times and identified interchanges. */
public final class AutomaticRoutePlanner {
    private static final int TRANSFER_SECONDS = 300;
    private static final int ALTERNATIVE_TRANSFER_COST = 9 * 60;
    private static final int ALTERNATIVE_SECONDS_SLACK = 25 * 60;
    private static final int MAX_ALTERNATIVES = 32;
    private static final int MAX_ALTERNATIVE_VISITS = 10_000;
    private static final int MAX_ORIGIN_DISTANCE_METERS = 1000;
    private final Map<String, Place> placesById = new LinkedHashMap<>();
    private final Map<String, Place> placesByStop = new HashMap<>();
    private final List<Place> destinations;

    public AutomaticRoutePlanner(Map<String, TransitLine> lines) {
        for (TransitLine line : lines.values()) {
            List<Platform> platforms = new ArrayList<>();
            for (Station station : line.stations) {
                // These two connections also exist in catalogues from before station groups were stored.
                String id = station.placeId;
                if (id.equals("par_4_46") || id.equals("35609")) id = "rail-35609";
                if (id.equals("par_4_273") || id.equals("37001")) id = "rail-37001";
                Place place = placesById.computeIfAbsent(id, key -> new Place(key, station));
                place.platforms.add(new Platform(line, station, place));
                Platform platform = place.platforms.get(place.platforms.size() - 1);
                placesByStop.putIfAbsent(station.id, place);
                if (!platforms.isEmpty()) {
                    int index = platforms.size();
                    connect(platforms.get(index - 1), platform, line.adjacentSeconds(index - 1, index));
                }
                platforms.add(platform);
            }
            if (line.closingSegmentSeconds > 0) {
                connect(platforms.get(platforms.size() - 1), platforms.get(0), line.closingSegmentSeconds);
            }
        }
        List<Place> sorted = new ArrayList<>(placesById.values());
        Collator spanish = Collator.getInstance(Locale.forLanguageTag("es-ES"));
        sorted.sort(Comparator.comparing((Place place) -> place.name, spanish).thenComparing(place -> place.id));
        destinations = Collections.unmodifiableList(sorted);
    }

    public List<Place> destinations() { return destinations; }

    public Place place(String id) {
        Place place = placesById.get(id);
        return place == null ? placesByStop.get(id) : place;
    }

    public boolean serves(Place place, String lineId) {
        if (place == null) return false;
        for (Platform platform : place.platforms) if (platform.line.id.equals(lineId)) return true;
        return false;
    }

    public List<String> lineNames(Place place) {
        Set<String> names = new LinkedHashSet<>();
        for (Platform platform : place.platforms) names.add(platform.line.name);
        return new ArrayList<>(names);
    }

    public Place nearest(double latitude, double longitude) {
        return nearest(latitude, longitude, MAX_ORIGIN_DISTANCE_METERS);
    }

    public Place nearest(double latitude, double longitude, double maxDistanceMeters) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180 || !(maxDistanceMeters > 0)) return null;
        double limit = maxDistanceMeters;
        Place nearest = null;
        for (Place place : destinations) {
            for (Platform platform : place.platforms) {
                Station station = platform.station;
                double distance = StationMatcher.distanceMeters(latitude, longitude, station.latitude, station.longitude);
                if (distance < limit) { nearest = place; limit = distance; }
            }
        }
        return nearest;
    }

    public JourneyPlan plan(double latitude, double longitude, String destinationId, int advance) {
        Place origin = nearest(latitude, longitude);
        if (origin == null) throw new IllegalArgumentException("Acércate a una estación para calcular tu viaje");
        return plan(origin.id, destinationId, advance);
    }

    public JourneyPlan plan(String originId, String destinationId, int advance) {
        return plan(originId, destinationId, advance, null);
    }

    public JourneyPlan plan(String originId, String destinationId, int advance, String preferredFirstLine) {
        return search(originId, destinationId, advance, preferredFirstLine, null);
    }

    /** Compare a few distinct itineraries by varying the departure or an interchange. */
    public List<JourneyPlan> alternatives(String originId, String destinationId, int advance) {
        List<JourneyPlan> candidates = routeCandidates(originId, destinationId, advance);
        return Collections.unmodifiableList(candidates.subList(0, Math.min(3, candidates.size())));
    }

    /** Explore complete itineraries with distinct transfers while keeping the fastest as a candidate. */
    public List<JourneyPlan> routeCandidates(String originId, String destinationId, int advance) {
        JourneyPlan fastest = plan(originId, destinationId, advance);
        Map<String, JourneyPlan> plans = new LinkedHashMap<>();
        plans.put(publicRouteKey(fastest), fastest);
        collectAlternatives(place(originId), place(destinationId), advance, plans);
        addOperatorRoute(plans, originId, destinationId, advance, false);
        addOperatorRoute(plans, originId, destinationId, advance, true);
        List<JourneyPlan> result = new ArrayList<>(plans.values());
        result.sort(Comparator.comparingInt(JourneyPlan::nominalSeconds)
                .thenComparingInt(plan -> plan.legs.size()).thenComparing(JourneyPlan::routeKey));
        return Collections.unmodifiableList(result);
    }

    private void addOperatorRoute(Map<String, JourneyPlan> plans, String origin, String destination, int advance, boolean renfe) {
        if (plans.values().stream().anyMatch(plan -> plan.legs.stream().allMatch(leg -> leg.line.realtimeAvailable == renfe))) return;
        try {
            JourneyPlan route = search(origin, destination, advance, null, null, line -> line.realtimeAvailable == renfe);
            plans.putIfAbsent(publicRouteKey(route), route);
        } catch (IllegalArgumentException unavailable) {
            // This operator does not connect this pair of stations on its own.
        }
    }

    private void collectAlternatives(Place origin, Place destination, int advance, Map<String, JourneyPlan> plans) {
        Map<Platform, Integer> remaining = remainingCosts(destination);
        int limit = origin.platforms.stream().mapToInt(platform -> remaining.getOrDefault(platform, Integer.MAX_VALUE))
                .min().orElse(0) + ALTERNATIVE_SECONDS_SLACK;
        PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator
                .comparingInt((Visit visit) -> visit.seconds + remaining.get(visit.platform))
                .thenComparingInt(visit -> visit.transfers)
                .thenComparingInt(visit -> remaining.get(visit.platform)));
        for (Platform platform : origin.platforms) {
            if (remaining.containsKey(platform)) queue.add(new Visit(platform, 0, 0, null, null));
        }
        for (int inspected = 0; !queue.isEmpty() && plans.size() < MAX_ALTERNATIVES && inspected < MAX_ALTERNATIVE_VISITS; inspected++) {
            Visit current = queue.remove();
            if (current.platform.place == destination) {
                JourneyPlan plan = assemble(current, advance);
                plans.putIfAbsent(publicRouteKey(plan), plan);
                continue;
            }
            for (Ride ride : current.platform.rides) {
                int seconds = current.seconds + ride.seconds;
                if (withinLimit(remaining, ride.to, seconds, limit) && !visited(current, ride.to.place)) {
                    queue.add(new Visit(ride.to, seconds, current.transfers, current, ride));
                }
            }
            if (current.incoming != null && current.transfers < 4) {
                int seconds = current.seconds + ALTERNATIVE_TRANSFER_COST;
                for (Platform transfer : current.platform.place.platforms) {
                    if (transfer.line != current.platform.line && withinLimit(remaining, transfer, seconds, limit)) {
                        queue.add(new Visit(transfer, seconds, current.transfers + 1, current, null));
                    }
                }
            }
        }
    }

    /** Relax the visited-place and transfer constraints to get a lower bound from every platform. */
    private Map<Platform, Integer> remainingCosts(Place destination) {
        PriorityQueue<PlatformCost> queue = new PriorityQueue<>(Comparator.comparingInt(PlatformCost::seconds));
        Map<Platform, Integer> remaining = new HashMap<>();
        for (Platform platform : destination.platforms) addCost(queue, remaining, platform, 0);
        while (!queue.isEmpty()) {
            PlatformCost current = queue.remove();
            if (current.seconds != remaining.get(current.platform)) continue;
            for (Ride ride : current.platform.rides) addCost(queue, remaining, ride.to, current.seconds + ride.seconds);
            for (Platform transfer : current.platform.place.platforms) {
                if (transfer.line != current.platform.line) {
                    addCost(queue, remaining, transfer, current.seconds + ALTERNATIVE_TRANSFER_COST);
                }
            }
        }
        return remaining;
    }

    private static void addCost(PriorityQueue<PlatformCost> queue, Map<Platform, Integer> costs, Platform platform, int seconds) {
        if (seconds >= costs.getOrDefault(platform, Integer.MAX_VALUE)) return;
        costs.put(platform, seconds);
        queue.add(new PlatformCost(platform, seconds));
    }

    private static boolean withinLimit(Map<Platform, Integer> remaining, Platform platform, int seconds, int limit) {
        return remaining.getOrDefault(platform, Integer.MAX_VALUE) <= limit - seconds;
    }

    private static boolean visited(Visit current, Place place) {
        for (Visit visit = current; visit != null; visit = visit.previous) {
            if (visit.platform.place == place) return true;
        }
        return false;
    }

    private String publicRouteKey(JourneyPlan plan) {
        StringJoiner publicRoute = new StringJoiner(";");
        for (JourneyPlan.Leg leg : plan.legs) {
            publicRoute.add(leg.line.name + "|" + place(leg.origin).id + "|" + place(leg.destination).id + "|" + leg.forward);
        }
        return publicRoute.toString();
    }

    /** Rebuild the selected itinerary from the catalog without choosing a different connection. */
    public JourneyPlan restore(String routeKey, String originId, String destinationId, int advance) {
        List<JourneyPlan.Leg> legs = new ArrayList<>();
        Place place = place(originId);
        String interchange = "";
        for (String entry : routeKey.split(";", -1)) {
            String[] fields = entry.split(",", -1);
            if (fields.length != 4 || (!fields[3].equals("0") && !fields[3].equals("1"))) throw unavailableRoute();
            String line = decode(fields[0]), startId = decode(fields[1]), endId = decode(fields[2]);
            Platform start = platform(line, startId), end = platform(line, endId);
            if (start == null || end == null || start.place != place || start == end) throw unavailableRoute();
            boolean forward = fields[3].equals("1");
            List<Station> stations = new ArrayList<>();
            List<Integer> seconds = new ArrayList<>();
            stations.add(start.station);
            Platform current = start;
            while (current != end) {
                Ride next = null;
                for (Ride ride : current.rides) if (ride.forward == forward) { next = ride; break; }
                if (next == null || next.to == start) throw unavailableRoute();
                current = next.to;
                stations.add(current.station);
                seconds.add(next.seconds);
            }
            if (legs.size() == 1) interchange = place.name;
            legs.add(new JourneyPlan.Leg(start.line, stations, seconds.stream().mapToInt(Integer::intValue).toArray(), forward, advance));
            place = end.place;
        }
        if (place != place(destinationId)) throw unavailableRoute();
        return new JourneyPlan(legs, interchange, (legs.size() - 1) * TRANSFER_SECONDS);
    }

    private static String decode(String id) {
        return new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8);
    }

    private static IllegalArgumentException unavailableRoute() {
        return new IllegalArgumentException("La ruta guardada ya no está disponible. Elige otra ruta.");
    }

    private Platform platform(String line, String station) {
        Place place = place(station);
        if (place == null) return null;
        for (Platform platform : place.platforms) {
            if (platform.line.id.equals(line) && platform.station.id.equals(station)) return platform;
        }
        return null;
    }

    /** Alternatives for ambiguous departures at interchanges, including both directions on circular lines. */
    public List<JourneyPlan> departurePlans(String originId, String destinationId, int advance) {
        Place origin = place(originId);
        if (origin == null || origin == place(destinationId)) return Collections.emptyList();
        List<JourneyPlan> plans = new ArrayList<>();
        for (Platform platform : origin.platforms) {
            for (Ride direction : platform.rides) {
                try {
                    plans.add(search(origin.id, destinationId, advance, platform.line.id, direction.forward));
                } catch (IllegalArgumentException unavailable) {
                    // This direction cannot complete the trip without doubling back.
                }
            }
        }
        plans.sort(Comparator.comparingInt(JourneyPlan::nominalSeconds));
        return Collections.unmodifiableList(plans);
    }

    private JourneyPlan search(String originId, String destinationId, int advance, String preferredFirstLine, Boolean firstForward) {
        return search(originId, destinationId, advance, preferredFirstLine, firstForward, line -> true);
    }

    private JourneyPlan search(String originId, String destinationId, int advance, String preferredFirstLine,
                               Boolean firstForward, Predicate<TransitLine> allowed) {
        Place origin = place(originId), destination = place(destinationId);
        if (origin == null || destination == null) throw new IllegalArgumentException("No se encuentra la estación");
        if (origin == destination) throw new IllegalArgumentException("Ya estás en el destino");
        PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator.comparingInt((Visit visit) -> visit.seconds)
                .thenComparingInt(visit -> visit.transfers));
        Map<Platform, Visit> best = new HashMap<>();
        for (Platform platform : origin.platforms) {
            if (allowed.test(platform.line) && (preferredFirstLine == null || platform.line.id.equals(preferredFirstLine))) {
                Visit start = new Visit(platform, 0, 0, null, null);
                queue.add(start);
                best.put(platform, start);
            }
        }
        while (!queue.isEmpty()) {
            Visit current = queue.remove();
            if (current != best.get(current.platform)) continue;
            if (current.platform.place == destination) return assemble(current, advance);
            for (Ride ride : current.platform.rides) {
                if (current.previous == null && firstForward != null && firstForward != ride.forward) continue;
                enqueue(queue, best, new Visit(ride.to, current.seconds + ride.seconds, current.transfers, current, ride));
            }
            // The origin can board any of its platforms initially, or stay on the observed line when replanning.
            if (current.platform.place != origin && current.incoming != null) {
                for (Platform platform : current.platform.place.platforms) {
                    if (platform.line != current.platform.line && allowed.test(platform.line)) {
                        enqueue(queue, best, new Visit(platform, current.seconds + TRANSFER_SECONDS,
                                current.transfers + 1, current, null));
                    }
                }
            }
        }
        throw new IllegalArgumentException("No hay ruta ferroviaria entre estas estaciones");
    }

    private static void connect(Platform first, Platform second, int seconds) {
        first.rides.add(new Ride(second, seconds, true));
        second.rides.add(new Ride(first, seconds, false));
    }

    private static void enqueue(PriorityQueue<Visit> queue, Map<Platform, Visit> best, Visit candidate) {
        Visit previous = best.get(candidate.platform);
        if (previous != null && (previous.seconds < candidate.seconds
                || previous.seconds == candidate.seconds && previous.transfers <= candidate.transfers)) return;
        best.put(candidate.platform, candidate);
        queue.add(candidate);
    }

    private static JourneyPlan assemble(Visit end, int advance) {
        List<Visit> path = new ArrayList<>();
        for (Visit visit = end; visit.previous != null; visit = visit.previous) path.add(visit);
        Collections.reverse(path);
        List<JourneyPlan.Leg> legs = new ArrayList<>();
        String firstTransfer = "";
        for (int i = 0; i < path.size(); ) {
            Visit head = path.get(i);
            if (head.incoming == null) {
                if (firstTransfer.isEmpty()) firstTransfer = head.platform.place.name;
                i++;
                continue;
            }
            TransitLine line = head.platform.line;
            boolean forward = head.incoming.forward;
            List<Station> stations = new ArrayList<>();
            List<Integer> segments = new ArrayList<>();
            stations.add(head.previous.platform.station);
            while (i < path.size()) {
                Visit visit = path.get(i);
                if (visit.incoming == null || visit.platform.line != line || visit.incoming.forward != forward) break;
                stations.add(visit.platform.station);
                segments.add(visit.incoming.seconds);
                i++;
            }
            legs.add(new JourneyPlan.Leg(line, stations, segments.stream().mapToInt(Integer::intValue).toArray(), forward, advance));
        }
        return new JourneyPlan(legs, firstTransfer, end.transfers * TRANSFER_SECONDS);
    }

    public static final class Place {
        public final String id;
        public final String name;
        public final double latitude;
        public final double longitude;
        private final List<Platform> platforms = new ArrayList<>();

        private Place(String id, Station representative) {
            this.id = id;
            name = representative.placeName;
            latitude = representative.latitude;
            longitude = representative.longitude;
        }

        @Override public String toString() { return name; }
    }

    private static final class Platform {
        final TransitLine line;
        final Station station;
        final Place place;
        final List<Ride> rides = new ArrayList<>(2);

        Platform(TransitLine line, Station station, Place place) {
            this.line = line;
            this.station = station;
            this.place = place;
        }
    }

    private static final class Ride {
        final Platform to;
        final int seconds;
        final boolean forward;

        Ride(Platform to, int seconds, boolean forward) {
            this.to = to;
            this.seconds = seconds;
            this.forward = forward;
        }
    }

    private record PlatformCost(Platform platform, int seconds) { }

    private static final class Visit {
        final Platform platform;
        final int seconds;
        final int transfers;
        final Visit previous;
        final Ride incoming;

        Visit(Platform platform, int seconds, int transfers, Visit previous, Ride incoming) {
            this.platform = platform;
            this.seconds = seconds;
            this.transfers = transfers;
            this.previous = previous;
            this.incoming = incoming;
        }
    }
}
