package es.yori.sleeptrain.data.realtime;

import com.google.transit.realtime.GtfsRealtime;
import java.io.IOException;
import java.util.*;

/** Full snapshots and differential deletions are merged per source, then indexed by exact trip ID. */
public final class FeedIndex {
    public long timestamp;
    private final Map<String, GtfsRealtime.FeedEntity> entities = new HashMap<>();
    private final Map<String, Long> received = new HashMap<>();
    public final Map<String, Long> updateTimes = new HashMap<>();
    public final Map<String, GtfsRealtime.VehiclePosition> vehicles = new HashMap<>();
    public final Map<String, GtfsRealtime.TripUpdate> updates = new HashMap<>();
    public final Map<String, GtfsRealtime.Alert> alerts = new HashMap<>();
    public boolean accept(byte[] bytes, long now) throws IOException {
        GtfsRealtime.FeedMessage message = GtfsRealtime.FeedMessage.parseFrom(bytes);
        if (!message.getHeader().hasTimestamp()) return false;
        long time = message.getHeader().getTimestamp() * 1000;
        if (!fresh(time, now) || time < timestamp) return false;
        if (message.getHeader().getIncrementality() != GtfsRealtime.FeedHeader.Incrementality.DIFFERENTIAL) { entities.clear(); received.clear(); }
        for (GtfsRealtime.FeedEntity entity : message.getEntityList()) {
            if (entity.getIsDeleted()) { entities.remove(entity.getId()); received.remove(entity.getId()); }
            else { entities.put(entity.getId(), entity); received.put(entity.getId(), time); }
        }
        timestamp = time; vehicles.clear(); updates.clear(); alerts.clear(); updateTimes.clear();
        for (GtfsRealtime.FeedEntity entity : entities.values()) {
            if (entity.hasVehicle() && entity.getVehicle().hasTrip()) vehicles.put(entity.getVehicle().getTrip().getTripId(), entity.getVehicle());
            if (entity.hasTripUpdate()) {
                String id = entity.getTripUpdate().getTrip().getTripId();
                updates.put(id, entity.getTripUpdate()); updateTimes.put(id, entity.getTripUpdate().hasTimestamp() ? entity.getTripUpdate().getTimestamp() * 1000 : received.get(entity.getId()));
            }
            if (entity.hasAlert()) alerts.put(entity.getId(), entity.getAlert());
        }
        return true;
    }
    public static boolean fresh(long measured, long now) { return measured > 0 && now - measured <= 60_000 && measured - now <= 10_000; }
}
