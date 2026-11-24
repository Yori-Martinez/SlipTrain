package es.yori.sleeptrain.domain;

import es.yori.sleeptrain.core.Station;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Immutable, operator-scoped schedule with in-memory ID indexes. No Android dependency. */
public final class TransitLine {
    public static final String RENFE = "RENFE_C5", METRO = "METRO_L3";
    public static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
    public final String id, name, source, fetchedAt, calendarEnd, notice;
    public final List<Station> stations;
    public final Set<String> routeIds, agencyIds;
    public final Map<String, ScheduledTrip> trips;
    public final boolean realtimeAvailable;
    public final int closingSegmentSeconds;
    private final int[] segments;
    private final Map<String, Integer> stationIndexes = new HashMap<>();

    public TransitLine(String id, String name, List<Station> stations, int[] segments,
                       Collection<ScheduledTrip> trips, Set<String> routeIds, Set<String> agencyIds,
                       String source, String fetchedAt, String calendarEnd, String notice) {
        this(id, name, stations, segments, trips, routeIds, agencyIds, source, fetchedAt, calendarEnd, notice, 0);
    }

    public TransitLine(String id, String name, List<Station> stations, int[] segments,
                       Collection<ScheduledTrip> trips, Set<String> routeIds, Set<String> agencyIds,
                       String source, String fetchedAt, String calendarEnd, String notice, int closingSegmentSeconds) {
        if (stations.size() < 2 || segments.length != stations.size() - 1) throw new IllegalArgumentException("Recorrido incompleto");
        for (int seconds : segments) if (seconds <= 0 || seconds > 1800) throw new IllegalArgumentException("Duración inválida");
        if (closingSegmentSeconds < 0 || closingSegmentSeconds > 1800) throw new IllegalArgumentException("Duración circular inválida");
        this.id = id; this.name = name; this.source = source; this.fetchedAt = fetchedAt;
        this.calendarEnd = calendarEnd; this.notice = notice;
        this.stations = Collections.unmodifiableList(new ArrayList<>(stations)); this.segments = segments.clone();
        this.routeIds = Collections.unmodifiableSet(new HashSet<>(routeIds));
        this.agencyIds = Collections.unmodifiableSet(new HashSet<>(agencyIds));
        Map<String, ScheduledTrip> index = new HashMap<>();
        for (ScheduledTrip trip : trips) index.put(trip.id, trip);
        this.trips = Collections.unmodifiableMap(index);
        for (int i = 0; i < stations.size(); i++) {
            if (stationIndexes.put(stations.get(i).id, i) != null) throw new IllegalArgumentException("ID de estación duplicado");
        }
        realtimeAvailable = id.startsWith("RENFE_");
        this.closingSegmentSeconds = closingSegmentSeconds;
    }

    public TransitLine withTrips(Collection<ScheduledTrip> schedules) {
        return new TransitLine(id, name, stations, segments, schedules, routeIds, agencyIds,
                source, fetchedAt, calendarEnd, notice, closingSegmentSeconds);
    }

    public int adjacentSeconds(int from, int to) {
        if (Math.abs(from - to) == 1) return segments[Math.min(from, to)];
        if (closingSegmentSeconds > 0 && Math.min(from, to) == 0 && Math.max(from, to) == stations.size() - 1) {
            return closingSegmentSeconds;
        }
        throw new IllegalArgumentException("Las estaciones no son consecutivas");
    }

    public String direction(boolean forward) {
        if (closingSegmentSeconds > 0) return forward ? "Sentido 1" : "Sentido 2";
        return stations.get(forward ? stations.size() - 1 : 0).name;
    }
    public int index(String station) {
        Integer result = stationIndexes.get(station);
        if (result == null) throw new IllegalArgumentException("Estación fuera de " + name);
        return result;
    }
    public List<Station> slice(String origin, String destination) {
        List<Station> result = new ArrayList<>(); int from = index(origin), to = index(destination), step = to >= from ? 1 : -1;
        for (int i = from; ; i += step) { result.add(stations.get(i)); if (i == to) return result; }
    }
    public int[] segments(String origin, String destination) {
        int from = index(origin), to = index(destination), step = to >= from ? 1 : -1;
        int[] result = new int[Math.abs(to - from)];
        for (int i = 0, at = from; i < result.length; i++, at += step) result[i] = segments[Math.min(at, at + step)];
        return result;
    }
    public int nominalSeconds(String origin, String destination) { int total = 0; for (int v : segments(origin, destination)) total += v; return total; }
    public List<Departure> departures(String origin, String destination, long now) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZONE).toLocalDate(); List<Departure> result = new ArrayList<>();
        for (ScheduledTrip trip : trips.values()) {
            Integer from = trip.indexes.get(origin), to = trip.indexes.get(destination);
            if (trip.frequencyBased || from == null || to == null || from >= to) continue;
            for (LocalDate date : Arrays.asList(today.minusDays(1), today)) {
                String key = date.format(DateTimeFormatter.BASIC_ISO_DATE);
                if (!trip.dates.contains(key)) continue;
                long departure = trip.timeAt(origin, key);
                if (departure >= now - 20 * 60_000 && departure <= now + 2 * 3600_000L) result.add(new Departure(trip, key, departure));
            }
        }
        result.sort(Comparator.comparingLong(d -> d.time));
        return new ArrayList<>(result.subList(0, Math.min(20, result.size())));
    }

    public static final class ScheduledTrip {
        public final String id, routeId;
        public final List<String> stopIds;
        public final int[] seconds, sequences;
        public final Set<String> dates;
        public final boolean frequencyBased;
        public final Map<String, Integer> indexes;
        public ScheduledTrip(String id, String routeId, List<String> stopIds, int[] seconds,
                             int[] sequences, Set<String> dates, boolean frequencyBased) {
            if (stopIds.size() < 2 || seconds.length != stopIds.size() || sequences.length != seconds.length) throw new IllegalArgumentException("Viaje incompleto");
            this.id = id; this.routeId = routeId; this.stopIds = Collections.unmodifiableList(new ArrayList<>(stopIds));
            this.seconds = seconds.clone(); this.sequences = sequences.clone(); this.dates = Collections.unmodifiableSet(new HashSet<>(dates));
            this.frequencyBased = frequencyBased; Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < stopIds.size(); i++) {
                if (i > 0 && (seconds[i] <= seconds[i - 1] || sequences[i] <= sequences[i - 1])) throw new IllegalArgumentException("Secuencia de viaje inválida");
                index.put(stopIds.get(i), i);
            }
            indexes = Collections.unmodifiableMap(index);
        }
        public long timeAt(String stop, String date) {
            Integer i = indexes.get(stop); if (i == null) return -1;
            return LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE).atTime(12, 0).atZone(ZONE)
                    .minusHours(12).plusSeconds(seconds[i]).toInstant().toEpochMilli();
        }
        public String stopForSequence(int sequence) {
            for (int i = 0; i < sequences.length; i++) if (sequences[i] == sequence) return stopIds.get(i);
            return "";
        }
    }
    public static final class Departure {
        public final ScheduledTrip trip;
        public final String date;
        public final long time;
        public Departure(ScheduledTrip trip, String date, long time) { this.trip = trip; this.date = date; this.time = time; }
        @Override public String toString() {
            return Instant.ofEpochMilli(time).atZone(ZONE).format(DateTimeFormatter.ofPattern("HH:mm"))
                    + " · tren " + trip.id.replaceFirst("^.*?([0-9]{5})C5$", "$1");
        }
    }
}
