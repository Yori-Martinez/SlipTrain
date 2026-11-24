package es.yori.sleeptrain.data.realtime;

import es.yori.sleeptrain.data.CatalogCodec;
import es.yori.sleeptrain.domain.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Opt-in network diagnostic, run with :app:probeFeeds; never part of the offline unit suite. */
public final class LiveFeedProbe {
    public static void main(String[] args) throws Exception {
        JSONObject source = new JSONObject(new String(Files.readAllBytes(Paths.get("src/main/assets/transit_catalog.json")), StandardCharsets.UTF_8));
        TransitLine c5 = CatalogCodec.decode(source.getJSONArray("lines").getJSONObject(0));
        RenfeRealtimeRepository repo = new RenfeRealtimeRepository();
        repo.poll(new RealtimeContext(null, "", "35606", "35607", c5.routeIds, c5.agencyIds, new HashSet<>(Arrays.asList("35606", "35607"))));
        long now = System.currentTimeMillis(); int joins = 0; RealtimeSnapshot example = null;
        for (String id : repo.positions.vehicles.keySet()) {
            TransitLine.ScheduledTrip trip = c5.trips.get(id); if (trip == null) continue; joins++;
            if (example == null) {
                String date = java.time.Instant.ofEpochMilli(now).atZone(TransitLine.ZONE).toLocalDate().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                if (!trip.dates.contains(date)) continue;
                example = repo.interpret(new RealtimeContext(trip, date, trip.stopIds.get(trip.stopIds.size() - 2), trip.stopIds.get(trip.stopIds.size() - 1), c5.routeIds, c5.agencyIds, new HashSet<>(trip.stopIds)), now);
            }
        }
        JSONObject result = new JSONObject().put("checkedAt", java.time.Instant.ofEpochMilli(now).toString())
                .put("positionsFresh", FeedIndex.fresh(repo.positions.timestamp, now)).put("tripUpdatesFresh", FeedIndex.fresh(repo.updates.timestamp, now))
                .put("alertsFresh", FeedIndex.fresh(repo.alerts.timestamp, now)).put("c5ExactTripIdJoins", joins)
                .put("exampleVehicleMatched", example != null && example.hasFreshVehicle);
        System.out.println(result.toString(2));
        if (!FeedIndex.fresh(repo.positions.timestamp, now) || !FeedIndex.fresh(repo.updates.timestamp, now) || !FeedIndex.fresh(repo.alerts.timestamp, now)) throw new IllegalStateException("Algún feed no respondió con datos recientes");
        if (joins == 0) throw new IllegalStateException("No hay vehículos C5 enlazables ahora; puede no haber servicio o haber cambiado el calendario");
    }
}
