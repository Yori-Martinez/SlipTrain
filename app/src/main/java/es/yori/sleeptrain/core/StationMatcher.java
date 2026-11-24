package es.yori.sleeptrain.core;

import java.util.List;

/** Matches fresh device fixes to the downloaded route, including brief station signal windows. */
public final class StationMatcher {
    public static final long MAX_AGE_MS = 15_000;
    public static final class Fix {
        public final double position;
        public final int stationIndex;
        public final long measuredElapsed;
        Fix(double position, int stationIndex, long measuredElapsed) {
            this.position = position; this.stationIndex = stationIndex; this.measuredElapsed = measuredElapsed;
        }
    }
    private double candidate = -1;
    private long candidateAt = -1, lastAccepted = -1;
    private double lastLat, lastLon;

    public int observe(List<Station> stations, double lat, double lon, float accuracy,
                       long measuredElapsed, long nowElapsed) {
        Fix fix = match(stations, lat, lon, accuracy, measuredElapsed, nowElapsed);
        return fix == null ? -1 : fix.stationIndex;
    }

    public Fix match(List<Station> stations, double lat, double lon, float accuracy,
                     long measuredElapsed, long nowElapsed) {
        long age = nowElapsed - measuredElapsed;
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180
                || !Float.isFinite(accuracy) || accuracy <= 0 || accuracy > 80 || age < 0 || age > MAX_AGE_MS
                || measuredElapsed <= lastAccepted) return null;
        // Reject teleports even if Android reports a small accuracy radius.
        if (lastAccepted >= 0 && distanceMeters(lastLat, lastLon, lat, lon)
                > 150 + (measuredElapsed - lastAccepted) * .065) return null;
        int nearest = -1;
        double best = Double.MAX_VALUE, second = Double.MAX_VALUE;
        for (int i = 0; i < stations.size(); i++) {
            Station s = stations.get(i);
            double distance = distanceMeters(lat, lon, s.latitude, s.longitude);
            if (distance < best) { second = best; best = distance; nearest = i; }
            else second = Math.min(second, distance);
        }
        double position = -1;
        int station = -1;
        if (nearest >= 0 && best <= 110 && second - best <= accuracy * 2) return null;
        if (nearest >= 0 && best <= 110) {
            position = nearest; station = nearest;
        } else if (accuracy <= 35) {
            // Station-to-station chords are only an approximation: reject fixes outside a narrow corridor.
            double limit = Math.max(60, accuracy * 2), corridor = Double.MAX_VALUE, runnerUp = Double.MAX_VALUE;
            int bestSegment = -1;
            for (int i = 0; i + 1 < stations.size(); i++) {
                Station a = stations.get(i), b = stations.get(i + 1);
                double scale = Math.cos(Math.toRadians(lat));
                double ax = (a.longitude - lon) * scale, ay = a.latitude - lat;
                double dx = (b.longitude - a.longitude) * scale, dy = b.latitude - a.latitude;
                double length = dx * dx + dy * dy;
                if (length == 0) continue;
                double t = -(ax * dx + ay * dy) / length;
                if (t < .05 || t > .95) continue;
                double distance = Math.hypot(ax + t * dx, ay + t * dy) * 111_195;
                if (distance < corridor) {
                    runnerUp = corridor; corridor = distance; position = i + t; bestSegment = i;
                } else runnerUp = Math.min(runnerUp, distance);
            }
            if (bestSegment < 0 || corridor > limit || runnerUp - corridor <= accuracy * 2) return null;
        }
        if (position < 0) { candidate = -1; return null; }
        boolean strong = accuracy <= 25 && (station < 0 || best + accuracy <= 85);
        if (!strong) {
            if (candidate < 0 || Math.abs(candidate - position) > .15 || measuredElapsed - candidateAt > 15_000) {
                candidate = position; candidateAt = measuredElapsed; return null;
            }
            if (measuredElapsed - candidateAt < 2_000) return null;
        }
        candidate = position; candidateAt = measuredElapsed;
        lastAccepted = measuredElapsed; lastLat = lat; lastLon = lon;
        return new Fix(position, station, measuredElapsed);
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
    }
}
