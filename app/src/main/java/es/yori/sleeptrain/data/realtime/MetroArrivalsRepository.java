package es.yori.sleeptrain.data.realtime;

import es.yori.sleeptrain.data.HttpClient;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/** Arrival board context only: this response cannot identify the passenger's train. */
public final class MetroArrivalsRepository {
    public static final String BASE = "https://serviciosapp.metromadrid.es/servicios/rest/teleindicadores/";
    public String load(String stopId, String direction) {
        String code = "par_4_46".equals(stopId) ? "0304" : "par_4_273".equals(stopId) ? "0357" : "";
        if (code.isEmpty()) return "Llegadas Metro no contrastadas para esta estación";
        try { return parse(new JSONObject(new String(HttpClient.get(BASE + code, 256 * 1024, 6_000), StandardCharsets.UTF_8)), stopId, direction, System.currentTimeMillis()); }
        catch (Exception error) { return "Teleindicadores Metro no disponibles"; }
    }
    public static String parse(JSONObject json, String stopId, String direction, long now) throws Exception {
        String expected = "par_4_46".equals(stopId) ? "Embajadores" : "par_4_273".equals(stopId) ? "Villaverde Alto" : "";
        if (expected.isEmpty()) return "Estación Metro sin correspondencia verificada";
        JSONArray values = json.optJSONArray("Vtelindicadores"); List<String> rows = new ArrayList<>();
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject row = values.getJSONObject(i);
            if (row.optInt("linea") != 3 || !expected.equalsIgnoreCase(row.optString("nombreest"))
                    || !direction.equalsIgnoreCase(row.optString("sentido"))) continue;
            long emitted; try { emitted = OffsetDateTime.parse(row.getString("fechaHoraEmisionPrevision")).toInstant().toEpochMilli(); } catch (Exception ignored) { continue; }
            if (!FeedIndex.fresh(emitted, now)) continue;
            String field = !row.isNull("proximo") && row.has("proximo") ? "proximo" : "siguiente";
            if (!row.has(field) || row.isNull(field)) continue;
            long arrival = emitted + row.getInt(field) * 60_000L;
            if (arrival < now - 30_000) continue;
            rows.add("Andén " + row.optInt("anden") + " hacia " + direction + ": " + Math.max(0, (arrival - now + 59_999) / 60_000) + " min");
        }
        return rows.isEmpty() ? "Sin llegadas L3 recientes para este sentido" : String.join(" · ", rows) + " (panel; no identifica tu tren)";
    }
}
