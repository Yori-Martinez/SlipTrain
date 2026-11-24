package es.yori.sleeptrain.data;

import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.domain.TransitLine;
import org.json.*;
import java.util.*;

public final class CatalogCodec {
    private CatalogCodec() { }
    public static TransitLine decode(JSONObject json) throws JSONException {
        List<Station> stations = new ArrayList<>(); JSONArray items = json.getJSONArray("stations");
        for (int i = 0; i < items.length(); i++) {
            JSONObject s = items.getJSONObject(i);
            double lat = s.getDouble("lat"), lon = s.getDouble("lon");
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) throw new JSONException("Coordenada inválida");
            String id = s.getString("id"), name = s.getString("name");
            stations.add(new Station(id, name, lat, lon, s.optString("placeId", id), s.optString("placeName", name)));
        }
        List<TransitLine.ScheduledTrip> trips = decodeTrips(json.getJSONArray("trips"));
        return new TransitLine(json.getString("id"), json.getString("name"), stations, ints(json.getJSONArray("segmentSeconds")), trips,
                new HashSet<>(strings(json.getJSONArray("routeIds"))), new HashSet<>(strings(json.optJSONArray("agencyIds"))),
                json.getString("source"), json.optString("fetchedAt"), json.optString("calendarEnd"), json.optString("notice"),
                json.optInt("closingSegmentSeconds"));
    }
    public static List<TransitLine.ScheduledTrip> decodeTrips(JSONArray values) throws JSONException {
        List<TransitLine.ScheduledTrip> trips = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) trips.add(decodeTrip(values.getJSONObject(i)));
        return trips;
    }
    public static TransitLine.ScheduledTrip decodeTrip(JSONObject t) throws JSONException {
        return new TransitLine.ScheduledTrip(t.getString("id"), t.getString("routeId"), strings(t.getJSONArray("stopIds")),
                ints(t.getJSONArray("times")), ints(t.getJSONArray("sequences")), new HashSet<>(strings(t.getJSONArray("dates"))), t.optBoolean("frequencyBased"));
    }
    public static JSONObject encodeTrip(TransitLine.ScheduledTrip t) throws JSONException {
        return new JSONObject().put("id", t.id).put("routeId", t.routeId).put("stopIds", new JSONArray(t.stopIds))
                .put("times", numbers(t.seconds)).put("sequences", numbers(t.sequences)).put("dates", new JSONArray(t.dates)).put("frequencyBased", t.frequencyBased);
    }
    public static List<String> strings(JSONArray array) throws JSONException {
        List<String> result = new ArrayList<>(); if (array != null) for (int i = 0; i < array.length(); i++) result.add(array.getString(i)); return result;
    }
    public static int[] ints(JSONArray array) throws JSONException {
        int[] result = new int[array.length()]; for (int i = 0; i < result.length; i++) result[i] = array.getInt(i); return result;
    }
    public static JSONArray numbers(int[] values) { JSONArray result = new JSONArray(); for (int value : values) result.put(value); return result; }
}
