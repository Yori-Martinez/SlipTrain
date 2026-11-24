package es.yori.sleeptrain.data;

import es.yori.sleeptrain.core.CsvReader;
import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.domain.TransitLine;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

public final class GtfsImporter {
    private interface Consumer { void accept(Map<String, String> row) throws Exception; }
    private GtfsImporter() { }
    public static JSONObject parse(File file, TransitLine reference) throws Exception {
        boolean metro = TransitLine.METRO.equals(reference.id);
        try (ZipFile zip = new ZipFile(file)) {
            Map<String, JSONObject> stops = new HashMap<>();
            rows(zip, "stops.txt", false, r -> stops.put(r.get("stop_id"), new JSONObject().put("id", r.get("stop_id"))
                    .put("name", r.get("stop_name")).put("lat", Double.parseDouble(r.get("stop_lat"))).put("lon", Double.parseDouble(r.get("stop_lon")))));
            Set<String> routeIds = new HashSet<>(), agencies = new HashSet<>();
            rows(zip, "agency.txt", true, r -> { if (r.containsKey("agency_id")) agencies.add(r.get("agency_id")); });
            rows(zip, "routes.txt", false, r -> {
                if (metro ? "3".equals(r.get("route_short_name")) || "L3".equals(r.get("route_short_name"))
                        : "C5".equals(r.get("route_short_name")) && r.get("route_id").startsWith("10")) routeIds.add(r.get("route_id"));
            });
            if (routeIds.isEmpty()) throw new IOException("La línea no está en el GTFS");
            Map<String, Map<String, String>> trips = new HashMap<>();
            rows(zip, "trips.txt", false, r -> { if (routeIds.contains(r.get("route_id"))) trips.put(r.get("trip_id"), r); });
            Map<String, List<StopTime>> times = new HashMap<>();
            Set<String> stationIds = new HashSet<>(); for (Station s : reference.stations) stationIds.add(s.id);
            rows(zip, "stop_times.txt", false, r -> {
                if (trips.containsKey(r.get("trip_id")) && stationIds.contains(r.get("stop_id"))) {
                    times.computeIfAbsent(r.get("trip_id"), ignored -> new ArrayList<>()).add(new StopTime(r.get("stop_id"), time(r.get("departure_time")), Integer.parseInt(r.get("stop_sequence"))));
                }
            });
            Map<String, Set<String>> dates = new HashMap<>(); List<String> calendarEnds = new ArrayList<>();
            String[] weekdays = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
            rows(zip, "calendar.txt", true, r -> {
                LocalDate start = date(r.get("start_date")), end = date(r.get("end_date"));
                if (end.isAfter(start.plusDays(400))) throw new IOException("Calendario demasiado largo");
                calendarEnds.add(r.get("end_date")); Set<String> days = dates.computeIfAbsent(r.get("service_id"), ignored -> new TreeSet<>());
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) if ("1".equals(r.get(weekdays[d.getDayOfWeek().getValue() - 1]))) days.add(d.format(DateTimeFormatter.BASIC_ISO_DATE));
            });
            rows(zip, "calendar_dates.txt", true, r -> {
                Set<String> days = dates.computeIfAbsent(r.get("service_id"), ignored -> new TreeSet<>());
                if ("1".equals(r.get("exception_type"))) { days.add(r.get("date")); calendarEnds.add(r.get("date")); }
                else days.remove(r.get("date"));
            });
            Set<String> frequencies = new HashSet<>(); rows(zip, "frequencies.txt", true, r -> frequencies.add(r.get("trip_id")));
            List<List<Integer>> samples = new ArrayList<>(); for (int i = 1; i < reference.stations.size(); i++) samples.add(new ArrayList<>());
            JSONArray outputTrips = new JSONArray();
            for (Map.Entry<String, List<StopTime>> entry : times.entrySet()) {
                List<StopTime> items = entry.getValue(); items.sort(Comparator.comparingInt(r -> r.sequence));
                if (items.size() < 2) continue;
                List<String> ids = new ArrayList<>(); int[] seconds = new int[items.size()], sequences = new int[items.size()];
                boolean valid = true; int first = reference.index(items.get(0).id), last = reference.index(items.get(items.size() - 1).id); int direction = last > first ? 1 : -1;
                for (int i = 0; i < items.size(); i++) {
                    StopTime r = items.get(i); ids.add(r.id); seconds[i] = r.seconds; sequences[i] = r.sequence;
                    if (i > 0) valid &= seconds[i] > seconds[i - 1] && (reference.index(ids.get(i)) - reference.index(ids.get(i - 1))) * direction > 0;
                }
                if (!valid) continue;
                for (int i = 1; i < ids.size(); i++) {
                    int a = reference.index(ids.get(i - 1)), b = reference.index(ids.get(i)), delta = seconds[i] - seconds[i - 1];
                    if (Math.abs(b - a) == 1 && delta >= 30 && delta <= 1800) samples.get(Math.min(a, b)).add(delta);
                }
                Set<String> active = dates.getOrDefault(trips.get(entry.getKey()).get("service_id"), Collections.emptySet());
                if (!active.isEmpty()) outputTrips.put(CatalogCodec.encodeTrip(new TransitLine.ScheduledTrip(entry.getKey(), trips.get(entry.getKey()).get("route_id"), ids, seconds, sequences, active, frequencies.contains(entry.getKey()))));
            }
            JSONArray stationArray = new JSONArray(), durations = new JSONArray();
            for (Station s : reference.stations) { if (!stops.containsKey(s.id)) throw new IOException("Falta la estación " + s.id); stationArray.put(stops.get(s.id)); }
            for (int i = 0; i < samples.size(); i++) {
                List<Integer> sample = samples.get(i); Collections.sort(sample);
                if (sample.isEmpty() && !metro) throw new IOException("Faltan tramos C5");
                durations.put(sample.isEmpty() ? reference.segments(reference.stations.get(i).id, reference.stations.get(i + 1).id)[0]
                        : sample.size() % 2 == 0 ? (sample.get(sample.size() / 2 - 1) + sample.get(sample.size() / 2)) / 2 : sample.get(sample.size() / 2));
            }
            if (!metro && outputTrips.length() == 0) throw new IOException("No hay viajes C5");
            String notice = metro ? "Sin GTFS-RT público verificado. " + (outputTrips.length() == 0 ? "GTFS sin viajes L3; tiempos provisionales. " : "")
                    + "El Casar usa la referencia del intercambiador; andén L3 no verificado." : "";
            return new JSONObject().put("id", reference.id).put("name", reference.name).put("source", metro ? TransitRepository.METRO_URL : TransitRepository.RENFE_URL)
                    .put("fetchedAt", Instant.now().toString()).put("calendarEnd", calendarEnds.isEmpty() ? "" : Collections.max(calendarEnds)).put("notice", notice)
                    .put("routeIds", new JSONArray(routeIds)).put("agencyIds", new JSONArray(agencies)).put("stations", stationArray).put("segmentSeconds", durations).put("trips", outputTrips);
        }
    }
    private static void rows(ZipFile zip, String name, boolean optional, Consumer consumer) throws Exception {
        ZipEntry entry = zip.getEntry(name); if (entry == null && optional) return;
        if (entry == null || entry.getSize() > 256L * 1024 * 1024) throw new IOException("GTFS inválido: " + name);
        try (CsvReader csv = new CsvReader(new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)))) {
            List<String> headers = csv.read(), values; if (headers == null) throw new IOException("CSV vacío");
            while ((values = csv.read()) != null) {
                if (values.size() != headers.size()) continue;
                Map<String, String> row = new HashMap<>(); for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), values.get(i)); consumer.accept(row);
            }
        }
    }
    private static LocalDate date(String value) { return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE); }
    private static final class StopTime {
        final String id;
        final int seconds, sequence;
        StopTime(String id, int seconds, int sequence) { this.id = id; this.seconds = seconds; this.sequence = sequence; }
    }
    private static int time(String value) {
        String[] f = value.split(":"); if (f.length != 3) throw new IllegalArgumentException("Hora inválida");
        int h = Integer.parseInt(f[0]), m = Integer.parseInt(f[1]), s = Integer.parseInt(f[2]);
        if (h < 0 || m < 0 || m > 59 || s < 0 || s > 59) throw new IllegalArgumentException("Hora inválida"); return h * 3600 + m * 60 + s;
    }
}
