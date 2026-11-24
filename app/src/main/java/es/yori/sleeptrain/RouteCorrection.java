package es.yori.sleeptrain;

import android.location.Location;
import es.yori.sleeptrain.core.StationMatcher;
import es.yori.sleeptrain.domain.AutomaticRoutePlanner;
import es.yori.sleeptrain.domain.AutomaticRoutePlanner.Place;
import es.yori.sleeptrain.domain.JourneyPlan;
import java.util.ArrayList;
import java.util.List;

/** Replans only after precise observations show forward movement on an alternative first leg. */
final class RouteCorrection {
    private final List<Candidate> candidates = new ArrayList<>();
    private Location first;

    JourneyPlan observe(AutomaticRoutePlanner planner, String destinationId, int advance,
                        Location location, long now) {
        if (!location.hasAccuracy() || location.getAccuracy() <= 0 || location.getAccuracy() > 25) return null;
        long measuredAt = location.getElapsedRealtimeNanos() / 1_000_000;
        if (measuredAt > now || now - measuredAt > StationMatcher.MAX_AGE_MS) return null;

        if (first != null) {
            long elapsed = (location.getElapsedRealtimeNanos() - first.getElapsedRealtimeNanos()) / 1_000_000;
            if (elapsed > 360_000) reset();
            else if (elapsed >= 12_000 && first.distanceTo(location) >= 220) {
                JourneyPlan match = null;
                for (Candidate candidate : candidates) {
                    StationMatcher.Fix fix = candidate.match(location, measuredAt, now);
                    if (fix == null || fix.position < .5) continue;
                    if (match != null) return null;
                    match = candidate.plan;
                }
                if (match != null) {
                    reset();
                    return match;
                }
            }
        }
        Place nearby = planner.nearest(location.getLatitude(), location.getLongitude(), 90);
        if (nearby == null || nearby == planner.place(destinationId)) return null;
        if (first != null && first.distanceTo(location) < 220) return null;
        reset();
        for (JourneyPlan proposed : planner.departurePlans(nearby.id, destinationId, advance)) {
            Candidate candidate = new Candidate(proposed);
            StationMatcher.Fix fix = candidate.match(location, measuredAt, now);
            if (fix != null && fix.stationIndex == 0) candidates.add(candidate);
        }
        if (!candidates.isEmpty()) first = new Location(location);
        return null;
    }

    void reset() {
        candidates.clear();
        first = null;
    }

    private static final class Candidate {
        final JourneyPlan plan;
        final StationMatcher matcher = new StationMatcher();

        Candidate(JourneyPlan plan) { this.plan = plan; }

        StationMatcher.Fix match(Location location, long measuredAt, long now) {
            return matcher.match(plan.legs.get(0).stations, location.getLatitude(), location.getLongitude(),
                    location.getAccuracy(), measuredAt, now);
        }
    }
}
