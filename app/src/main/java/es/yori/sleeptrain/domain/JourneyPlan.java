package es.yori.sleeptrain.domain;

import es.yori.sleeptrain.core.Station;
import java.util.*;

public final class JourneyPlan {
    public final List<Leg> legs;
    public final String transferName;
    public final int walkingSeconds;
    public JourneyPlan(List<Leg> legs, String transferName, int walkingSeconds) {
        if (legs.isEmpty()) throw new IllegalArgumentException("Ya estás en el destino");
        this.legs = Collections.unmodifiableList(new ArrayList<>(legs));
        this.transferName = transferName; this.walkingSeconds = walkingSeconds;
    }
    public int nominalSeconds() { int seconds = walkingSeconds; for (Leg leg : legs) for (int duration : leg.segments) seconds += duration; return seconds; }
    public static final class Leg {
        public final TransitLine line;
        public final String origin, destination;
        public final List<Station> stations;
        public final int[] segments;
        public final int advance;
        public final String direction;
        public final boolean forward;
        public Leg(TransitLine line, String origin, String destination, int advance) {
            this(line, line.slice(origin, destination), line.segments(origin, destination),
                    line.index(origin) < line.index(destination), advance);
        }
        Leg(TransitLine line, List<Station> stations, int[] segments, boolean forward, int advance) {
            if (stations.size() < 2) throw new IllegalArgumentException("El tramo no tiene recorrido");
            this.line = line;
            this.stations = Collections.unmodifiableList(new ArrayList<>(stations));
            this.segments = segments.clone();
            this.origin = stations.get(0).id;
            this.destination = stations.get(stations.size() - 1).id;
            this.forward = forward;
            this.direction = line.direction(forward);
            this.advance = Math.min(Math.max(0, advance), stations.size() - 1);
        }
        public Station alertStation() { return stations.get(stations.size() - 1 - advance); }
    }

    /** Stable identifier of the chosen lines, boarding and alighting stops, and directions. */
    public String routeKey() {
        StringJoiner route = new StringJoiner(";");
        for (Leg leg : legs) {
            route.add(String.join(",", encode(leg.line.id), encode(leg.origin), encode(leg.destination),
                    leg.forward ? "1" : "0"));
        }
        return route.toString();
    }

    private static String encode(String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
