package es.yori.sleeptrain.data;

import android.content.Context;
import android.util.AtomicFile;
import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.domain.TransitLine;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local files are a cache; all joins and indexes are in memory, without SQLite/Room. */
public final class TransitRepository {
    public static final String RENFE_URL = "https://ssl.renfe.com/ftransit/Fichero_CER_FOMENTO/fomento_transit.zip";
    public static final String METRO_URL = "https://crtm.maps.arcgis.com/sharing/rest/content/items/5c7f2951962540d69ffe8f640d94c246/data";
    private final Context context;
    public TransitRepository(Context context) { this.context = context.getApplicationContext(); }
    public Map<String, TransitLine> load() throws Exception {
        Map<String, TransitLine> lines = new LinkedHashMap<>();
        try (InputStream input = context.getAssets().open("transit_catalog.json")) {
            JSONArray values = new JSONObject(HttpClient.string(input, 24 * 1024 * 1024)).getJSONArray("lines");
            for (int i = 0; i < values.length(); i++) {
                TransitLine line = CatalogCodec.decode(values.getJSONObject(i));
                try (InputStream cached = file(line.id).openRead()) {
                    JSONObject data = new JSONObject(HttpClient.string(cached, 24 * 1024 * 1024));
                    if (data.optString("calendarEnd").compareTo(line.calendarEnd) >= 0) {
                        preserveStationGroups(data, line);
                        TransitLine updated = CatalogCodec.decode(data);
                        if (updated.id.equals(line.id)) line = updated;
                    }
                } catch (Exception ignored) { /* Retain packaged, validated reference. */ }
                lines.put(line.id, line);
            }
        }
        return Collections.unmodifiableMap(lines);
    }

    public TransitLine loadRealtimeLine(String id) throws Exception {
        return loadRealtimeLine(load().get(id));
    }

    public TransitLine loadRealtimeLine(TransitLine line) throws Exception {
        if (line == null || !line.realtimeAvailable || !line.trips.isEmpty()) return line;
        try (InputStream input = context.getAssets().open("regional_trips/" + line.id + ".json")) {
            return line.withTrips(CatalogCodec.decodeTrips(new JSONArray(HttpClient.string(input, 24 * 1024 * 1024))));
        } catch (FileNotFoundException missing) {
            return line;
        }
    }

    private static void preserveStationGroups(JSONObject data, TransitLine reference) throws JSONException {
        JSONArray stations = data.getJSONArray("stations");
        for (int i = 0; i < stations.length(); i++) {
            JSONObject station = stations.getJSONObject(i);
            Station original = reference.stations.get(reference.index(station.getString("id")));
            station.put("placeId", original.placeId).put("placeName", original.placeName);
        }
    }

    public TransitLine refresh(TransitLine reference) throws Exception {
        if (!TransitLine.RENFE.equals(reference.id) && !TransitLine.METRO.equals(reference.id)) return reference;
        String url = TransitLine.RENFE.equals(reference.id) ? RENFE_URL : METRO_URL;
        byte[] bytes = HttpClient.get(url, 32 * 1024 * 1024, 30_000);
        File zip = File.createTempFile("gtfs-", ".zip", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(zip)) { output.write(bytes); }
            JSONObject json = GtfsImporter.parse(zip, reference);
            preserveStationGroups(json, reference);
            TransitLine line = CatalogCodec.decode(json);
            AtomicFile atomic = file(reference.id); FileOutputStream output = atomic.startWrite();
            try { output.write(json.toString().getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(output); }
            catch (Exception error) { atomic.failWrite(output); throw error; }
            return line;
        } finally { if (!zip.delete()) zip.deleteOnExit(); }
    }
    private AtomicFile file(String id) { return new AtomicFile(new File(context.getFilesDir(), "line_" + id + ".json")); }
}
