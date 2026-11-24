package es.yori.sleeptrain;

import android.location.Location;
import android.os.SystemClock;

import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.core.StationMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Recognizes movement along a known rail leg from timestamped, route-matched fixes. */
public final class MovementEvidence {
    private static final long SEQUENCE_MS = 8 * 60_000;
    private static final double RAIL_SPEED_MPS = 4.5;
    private final List<Station> stations;
    private final StationMatcher matcher = new StationMatcher();
    private double firstPosition = -1;
    private double firstLat;
    private double firstLon;
    private long firstAt;
    private int stationCount;
    private int lastStation = -1;
    private int fastSamples;
    private double lastFastPosition;

    public MovementEvidence(List<Station> stations) {
        if (stations.size() < 2) throw new IllegalArgumentException("Se necesitan dos estaciones");
        this.stations = Collections.unmodifiableList(new ArrayList<>(stations));
    }

    public boolean observe(Location location) {
        float speed = location.hasSpeed() ? location.getSpeed() : Float.NaN;
        if (location.hasSpeedAccuracy()) speed -= location.getSpeedAccuracyMetersPerSecond();
        return observe(location.getLatitude(), location.getLongitude(), location.hasAccuracy() ? location.getAccuracy() : Float.NaN,
                speed, location.getElapsedRealtimeNanos() / 1_000_000L, SystemClock.elapsedRealtime());
    }

    boolean observe(double lat, double lon, float accuracy, float speed, long measuredAt, long now) {
        StationMatcher.Fix fix = matcher.match(stations, lat, lon, accuracy, measuredAt, now);
        if (fix == null) return false;
        if (firstPosition < 0 || measuredAt - firstAt > SEQUENCE_MS || fix.position < firstPosition - .2) {
            // The clock can open the window as the passenger leaves the origin; reject unrelated mid-route positions.
            if (fix.position > 1.05) { firstPosition = -1; return false; }
            reset(lat, lon, fix, speed);
            return false;
        }
        if (fix.position <= firstPosition + .01) {
            if (measuredAt - firstAt > 15_000 && stationCount <= 1) reset(lat, lon, fix, speed);
            return false;
        }
        if (fast(speed) && fix.position - lastFastPosition >= .04) {
            fastSamples++;
            lastFastPosition = fix.position;
        }
        if (fix.stationIndex >= 0 && fix.stationIndex > lastStation) {
            stationCount = lastStation >= 0 && fix.stationIndex == lastStation + 1 ? stationCount + 1 : 1;
            lastStation = fix.stationIndex;
        }
        long elapsed = measuredAt - firstAt;
        double distance = StationMatcher.distanceMeters(firstLat, firstLon, lat, lon);
        if (elapsed < 8_000 || fix.position - firstPosition < .15 || distance < 130) return false;
        if (stationCount >= 3) return true;
        return distance >= 170 && (distance * 1000 / elapsed >= RAIL_SPEED_MPS || fastSamples >= 2);
    }

    private void reset(double lat, double lon, StationMatcher.Fix fix, float speed) {
        firstPosition = fix.position;
        firstLat = lat;
        firstLon = lon;
        firstAt = fix.measuredElapsed;
        lastStation = fix.stationIndex;
        stationCount = lastStation >= 0 ? 1 : 0;
        fastSamples = fast(speed) ? 1 : 0;
        lastFastPosition = fix.position;
    }

    private static boolean fast(float speed) { return Float.isFinite(speed) && speed >= RAIL_SPEED_MPS && speed <= 65; }
}
