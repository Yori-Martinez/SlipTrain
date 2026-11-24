package es.yori.sleeptrain.core;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Fuses device position with operator data; all deadlines use monotonic elapsed realtime. */
public final class JourneyEngine {
    public static final long REMOTE_MAX_AGE_MS = 60_000, GPS_PRIORITY_MS = 30_000;
    private final List<Station> stations;
    private final int[] segmentSeconds;
    private final int alertIndex;
    private final long startedAt;
    private final boolean arrivalOnly;
    private int confirmedIndex;
    private double position, pace = 1;
    private long anchorAt, lastGpsAt = -1;
    private int calibrationIndex = -1;
    private long calibrationAt;
    private boolean reachedAlert;
    private long predictionDeadline, predictionUntil;

    public JourneyEngine(List<Station> stations, int[] segmentSeconds, int advanceStops,
                         long startedAt, int confirmedIndex, long anchorAt) {
        this(stations, segmentSeconds, advanceStops, startedAt, confirmedIndex, anchorAt, false);
    }

    public JourneyEngine(List<Station> stations, int[] segmentSeconds, int advanceStops,
                         long startedAt, int confirmedIndex, long anchorAt, boolean arrivalOnly) {
        if (stations.size() < 2 || segmentSeconds.length != stations.size() - 1) throw new IllegalArgumentException("Recorrido incompleto");
        if (advanceStops < 0 || advanceStops >= stations.size()) throw new IllegalArgumentException("Antelación fuera del recorrido");
        for (int seconds : segmentSeconds) if (seconds <= 0) throw new IllegalArgumentException("Duración inválida");
        this.stations = Collections.unmodifiableList(new ArrayList<>(stations));
        this.segmentSeconds = segmentSeconds.clone(); this.alertIndex = stations.size() - 1 - advanceStops;
        this.arrivalOnly = arrivalOnly;
        this.startedAt = startedAt; this.confirmedIndex = Math.max(0, Math.min(confirmedIndex, stations.size() - 1));
        this.position = this.confirmedIndex; this.anchorAt = anchorAt; this.reachedAlert = this.position >= alertIndex;
    }

    public void restorePosition(double position, long gpsAt, double pace, int calibrationIndex, long calibrationAt, boolean reached) {
        if (Double.isFinite(position) && position >= 0 && position <= stations.size() - 1) this.position = position;
        this.lastGpsAt = gpsAt;
        if (Double.isFinite(pace) && pace >= .6 && pace <= 1.8) this.pace = pace;
        this.calibrationIndex = calibrationIndex; this.calibrationAt = calibrationAt;
        this.reachedAlert = reached || this.position >= alertIndex;
    }

    public void confirmStation(int index, long now) {
        if (index <= confirmedIndex || index >= stations.size()) return;
        confirmedIndex = index; position = index; anchorAt = now;
        if (index >= alertIndex) reachedAlert = true;
    }

    /** A new reliable device fix can correct an estimate in either direction, including origin dwell. */
    public boolean correctPosition(double progress, int stationIndex, long measuredElapsed, long now) {
        if (!Double.isFinite(progress) || progress < 0 || progress > stations.size() - 1
                || measuredElapsed < startedAt || measuredElapsed <= lastGpsAt
                || now - measuredElapsed < 0 || now - measuredElapsed > StationMatcher.MAX_AGE_MS) return false;
        if (stationIndex >= 0) {
            if (calibrationIndex >= 0 && stationIndex > calibrationIndex && measuredElapsed > calibrationAt) {
                double nominal = nominalMillis(calibrationIndex, stationIndex);
                double observed = (measuredElapsed - calibrationAt) / nominal;
                // Long signal gaps/stops must not turn one sample into an unlimited postponement.
                if (observed >= .6 && observed <= 1.8) pace = pace * .5 + observed * .5;
            }
            // Keep the first arrival time while dwelling at the same station.
            if (stationIndex != calibrationIndex) { calibrationIndex = stationIndex; calibrationAt = measuredElapsed; }
        }
        position = progress; confirmedIndex = (int) Math.floor(progress); anchorAt = measuredElapsed;
        lastGpsAt = measuredElapsed; reachedAlert = progress >= alertIndex;
        predictionDeadline = 0; predictionUntil = 0;
        return true;
    }

    public boolean observeRemote(String stopId, String status, long measuredWall, long nowWall, long nowElapsed) {
        long age = nowWall - measuredWall;
        if (age < -10_000 || age > REMOTE_MAX_AGE_MS || gpsFresh(nowElapsed)
                || lastGpsAt >= 0 && nowElapsed - Math.max(0, age) <= lastGpsAt) return false;
        for (int i = 0; i < stations.size(); i++) {
            if (!stations.get(i).id.equals(stopId)) continue;
            if ("STOPPED_AT".equals(status)) confirmStation(i, nowElapsed - Math.max(0, age));
            if (i >= alertIndex && ("STOPPED_AT".equals(status) || !arrivalOnly && "INCOMING_AT".equals(status))) reachedAlert = true;
            return "STOPPED_AT".equals(status) || "INCOMING_AT".equals(status);
        }
        return false;
    }

    private double nominalMillis(double from, double to) {
        double total = 0;
        for (int i = (int) Math.floor(from); i < Math.ceil(to) && i < segmentSeconds.length; i++) {
            total += Math.max(0, Math.min(to, i + 1) - Math.max(from, i)) * segmentSeconds[i] * 1000;
        }
        return total;
    }
    /** Fixed small margin, rather than a percentage that accumulates several stops over a long journey. */
    public long deadline() {
        if (position >= alertIndex || reachedAlert) return anchorAt;
        long margin = arrivalOnly ? 0 : 15_000;
        return Math.max(startedAt + 1_000, anchorAt + Math.max(1_000, Math.round(nominalMillis(position, alertIndex) * pace) - margin));
    }
    public void prediction(long deadlineElapsed, long validUntilElapsed) {
        predictionDeadline = deadlineElapsed; predictionUntil = validUntilElapsed;
    }
    public long deadline(long now) {
        if (reachedAlert) return anchorAt;
        if (gpsFresh(now)) return Math.max(deadline(), lastGpsAt + GPS_PRIORITY_MS + 1);
        return now < predictionUntil && predictionDeadline > 0 ? predictionDeadline : deadline();
    }
    public boolean gpsFresh(long now) { return lastGpsAt >= 0 && now >= lastGpsAt && now - lastGpsAt <= GPS_PRIORITY_MS; }
    public boolean usesPrediction(long now) { return !gpsFresh(now) && now < predictionUntil && predictionDeadline > 0; }
    public long predictionDeadline() { return predictionDeadline; }
    public long predictionUntil() { return predictionUntil; }
    public boolean shouldAlert(long now) { return reachedAlert || now >= deadline(now); }
    public boolean shouldAlertForIncident(String critical, long now) {
        return critical != null && !critical.isEmpty() && !gpsFresh(now);
    }
    public int confirmedIndex() { return confirmedIndex; }
    public double position() { return position; }
    public double pace() { return pace; }
    public long lastGpsAt() { return lastGpsAt; }
    public int calibrationIndex() { return calibrationIndex; }
    public long calibrationAt() { return calibrationAt; }
    public boolean reachedAlert() { return reachedAlert; }
    public long anchorAt() { return anchorAt; }
    public int alertIndex() { return alertIndex; }
    public Station alertStation() { return stations.get(alertIndex); }
}
