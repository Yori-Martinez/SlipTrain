package es.yori.sleeptrain;

import android.content.*;
import android.location.Location;
import android.os.SystemClock;
import android.provider.Settings;
import es.yori.sleeptrain.core.*;
import es.yori.sleeptrain.data.CatalogCodec;
import es.yori.sleeptrain.data.realtime.RealtimeContext;
import es.yori.sleeptrain.domain.*;
import org.json.*;
import java.util.*;

/** Android persistence adapter. Catalog indexes live in memory; only a compact active plan is saved. */
final class SessionStore {
    static final String RIDING = "RIDING", WAITING = "WAITING_BOARDING", WAITING_LOCATION = "WAITING_LOCATION";
    static final String ADVANCE_PREFERENCE = "arrivalAdvance";
    private final Context context;
    private final SharedPreferences prefs;
    SessionStore(Context context) { this.context = context.getApplicationContext(); prefs = context.getSharedPreferences("journey", Context.MODE_PRIVATE); }
    JSONObject load() {
        try {
            JSONObject session = new JSONObject(prefs.getString("active", ""));
            int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            if (session.getInt("boot") != boot || session.getLong("startedElapsed") > SystemClock.elapsedRealtime()) { clear(); return null; }
            return session.put("gps", true).put("audioMode", "auto").put("speakerFallback", true);
        } catch (Exception ignored) { return null; }
    }
    JSONObject create(JourneyPlan plan, boolean vibration) throws Exception {
        return createAutomatic(plan, vibration, "", "", "", null);
    }

    JSONObject createAutomatic(JourneyPlan plan, boolean vibration, String audioId, String destinationId,
                               String profileId, Location initialFix) throws Exception {
        return createJourney(plan, vibration, audioId, destinationId, profileId, initialFix, true, false);
    }

    JSONObject createManual(JourneyPlan plan, boolean vibration, String audioId, String destinationId, String destinationName,
                            int advance, Location initialFix, boolean transferAlarm) throws Exception {
        if (plan != null) return createJourney(plan, vibration, audioId, destinationId, "", initialFix, transferAlarm, true);
        JSONObject pending = newJourney(vibration, audioId, destinationId, "", transferAlarm, advance)
                .put("destinationName", destinationName).put("phase", WAITING_LOCATION)
                .put("token", UUID.randomUUID().toString()).put("startedElapsed", SystemClock.elapsedRealtime());
        persist(pending);
        return pending;
    }

    JSONObject createScheduled(JourneyPlan plan, CommuteProfile profile, CommuteScheduler.Occurrence occurrence,
                                Location fix) throws Exception {
        return createJourney(plan, profile.vibration, profile.audioId, occurrence.destinationId, profile.id,
                fix, profile.transferAlarm, false);
    }

    private JSONObject createJourney(JourneyPlan plan, boolean vibration, String audioId, String destinationId, String profileId,
                                     Location initialFix, boolean transferAlarm, boolean waitForLocation) throws Exception {
        int advance = waitForLocation ? configuredAdvance(context) : plan.legs.get(plan.legs.size() - 1).advance;
        JSONObject session = newJourney(vibration, audioId, destinationId, profileId, transferAlarm, advance);
        applyPlan(session, plan);
        if (!seedPosition(session, initialFix) && waitForLocation) session.put("phase", WAITING_LOCATION);
        persist(session);
        return session;
    }

    private JSONObject newJourney(boolean vibration, String audioId, String destinationId, String profileId,
                                  boolean transferAlarm, int advance) throws Exception {
        if (load() != null) throw new IllegalStateException("Ya hay un viaje en curso");
        int transferAdvance = destinationId.isEmpty() ? -1 : transferAlarm ? configuredAdvance(context) : 0;
        return new JSONObject()
                .put("audioMode", "auto").put("vibration", vibration).put("speakerFallback", true).put("gps", true)
                .put("audioId", audioId).put("destinationId", destinationId).put("profileId", profileId)
                .put("transferAlarm", transferAlarm).put("arrivalAdvance", advance).put("transferAdvance", transferAdvance)
                .put("boot", Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1));
    }

    private void applyPlan(JSONObject session, JourneyPlan plan) throws Exception {
        int advance = session.optInt("transferAdvance", session.optBoolean("transferAlarm", true) ? configuredAdvance(context) : 0);
        session.put("legs", encodeLegs(plan, advance)).put("legIndex", 0).put("transferName", plan.transferName);
        activate(session, 0);
    }

    static int configuredAdvance(Context context) {
        return Math.max(0, Math.min(3, context.getSharedPreferences("preferences", Context.MODE_PRIVATE)
                .getInt(ADVANCE_PREFERENCE, 1)));
    }

    private static JSONArray encodeLegs(JourneyPlan plan, int transferAdvance) throws Exception {
        JSONArray legs = new JSONArray();
        for (int i = 0; i < plan.legs.size(); i++) {
            JourneyPlan.Leg leg = plan.legs.get(i);
            JSONArray stops = new JSONArray();
            for (Station s : leg.stations) {
                stops.put(new JSONObject().put("id", s.id).put("name", s.name).put("lat", s.latitude).put("lon", s.longitude));
            }
            int advance = i < plan.legs.size() - 1 && transferAdvance >= 0
                    ? Math.min(transferAdvance, leg.stations.size() - 1) : leg.advance;
            legs.put(new JSONObject().put("lineId", leg.line.id).put("lineName", leg.line.name).put("direction", leg.direction)
                    .put("stations", stops).put("segments", CatalogCodec.numbers(leg.segments))
                    .put("advance", advance)
                    .put("realTime", leg.line.realtimeAvailable)
                    .put("routeIds", new JSONArray(leg.line.routeIds)).put("agencyIds", new JSONArray(leg.line.agencyIds)));
        }
        return legs;
    }

    JSONObject replaceRoute(JSONObject previous, JourneyPlan plan, Location fix) throws Exception {
        JSONObject current = load();
        if (current == null || !current.optString("token").equals(previous.optString("token"))
                || !(RIDING.equals(current.optString("phase")) || WAITING.equals(current.optString("phase")))
                || current.optString("destinationId").isEmpty() || current.optBoolean("ringing") || current.optBoolean("forceAlarm")) {
            throw new IllegalStateException("El viaje ya cambió");
        }
        applyPlan(current, plan);
        if (!seedPosition(current, fix)) throw new IllegalArgumentException("La ubicación no confirma la nueva ruta");
        current.put("status", "Ruta actualizada con tu ubicación").put("reroutedElapsed", SystemClock.elapsedRealtime());
        persist(current);
        return current;
    }

    private static boolean seedPosition(JSONObject session, Location fix) throws Exception {
        return seedPosition(session, fix, new StationMatcher());
    }

    private static boolean seedPosition(JSONObject session, Location fix, StationMatcher matcher) throws Exception {
        if (fix == null || !fix.hasAccuracy()) return false;
        long now = SystemClock.elapsedRealtime();
        long measured = fix.getElapsedRealtimeNanos() / 1_000_000;
        StationMatcher.Fix matched = matcher.match(stations(session), fix.getLatitude(), fix.getLongitude(),
                fix.getAccuracy(), measured, now);
        if (matched == null) return false;
        session.put("startedElapsed", measured).put("startedWall", System.currentTimeMillis() - (now - measured));
        JourneyEngine engine = engine(session);
        if (engine.correctPosition(matched.position, matched.stationIndex, measured, now)) {
            capture(session, engine, now);
            session.put("positionSource", "GPS").put("gpsAccuracy", Math.round(fix.getAccuracy()))
                    .put("gpsState", "Ubicación recibida").put("status", "Ubicación del móvil");
            return true;
        }
        return false;
    }

    JSONObject startPendingLocation(String token, Location fix, StationMatcher matcher, JourneyPlan initialPlan) throws Exception {
        JSONObject session = load();
        if (session == null || !WAITING_LOCATION.equals(session.optString("phase"))
                || !token.equals(session.optString("token"))) return null;
        boolean replacing = initialPlan != null && !needsOrigin(session);
        if (initialPlan == null) {
            if (needsOrigin(session)) return null;
            activate(session, 0);
        } else {
            applyPlan(session, initialPlan);
        }
        if (!seedPosition(session, fix, matcher)) return null;
        if (replacing) session.put("status", "Ruta actualizada con tu ubicación").put("reroutedElapsed", SystemClock.elapsedRealtime());
        persist(session);
        return session;
    }

    JSONObject continueJourney() throws Exception { return continueJourney(null); }

    JSONObject continueJourney(Location fix) throws Exception {
        JSONObject session = load();
        if (session == null || !WAITING.equals(session.optString("phase"))) throw new IllegalStateException("No hay transbordo pendiente");
        activate(session, session.getInt("legIndex"));
        seedPosition(session, fix);
        persist(session);
        return session;
    }
    private void activate(JSONObject session, int index) throws Exception {
        JSONObject leg = session.getJSONArray("legs").getJSONObject(index);
        for (String key : Arrays.asList("lineId", "lineName", "direction", "stations", "segments", "advance", "routeIds", "agencyIds")) session.put(key, leg.get(key));
        session.put("realTime", leg.optBoolean("realTime", TransitLine.RENFE.equals(leg.optString("lineId"))));
        long now = SystemClock.elapsedRealtime();
        session.put("gps", true).put("audioMode", "auto").put("speakerFallback", true).put("autoAssociated", false)
                .put("token", UUID.randomUUID().toString()).put("phase", RIDING).put("startedElapsed", now).put("startedWall", System.currentTimeMillis())
                .put("anchorElapsed", now).put("confirmed", 0).put("ringing", false).put("forceAlarm", false)
                .put("position", 0).put("lastGpsElapsed", -1).put("pace", 1).put("calibrationIndex", -1).put("calibrationElapsed", 0)
                .put("reachedAlert", false).put("transferAcknowledged", false)
                .put("positionSource", "ESTIMATE").put("gpsAccuracy", 0).put("gpsState", gpsState(session))
                .put("predictionDeadlineElapsed", 0).put("predictionUntilElapsed", 0).put("tripId", "")
                .put("serviceDate", "").put("trip", JSONObject.NULL)
                .put("status", "Estimación local · esperando datos").put("realtimeInfo", "").put("alerts", "")
                .put("alarmReason", "Aviso anticipado por estimación local");
        session.put("deadlineElapsed", engine(session).deadline(now));
    }
    JSONObject acknowledgeTransfer(String token) throws Exception {
        JSONObject session = load();
        if (session == null || !token.equals(session.optString("token")) || !session.optBoolean("ringing")
                || !RIDING.equals(session.optString("phase")) || !hasTransfer(session)) return null;
        // Resume the same leg after an early acknowledgement and track the actual interchange.
        if (!session.optString("destinationId").isEmpty() && session.optInt("advance") > 0
                && session.optDouble("position", 0) < session.getJSONArray("stations").length() - 1) {
            session.put("transferAcknowledged", true).put("advance", 0).put("reachedAlert", false)
                    .put("ringing", false).put("forceAlarm", false).put("token", UUID.randomUUID().toString())
                    .put("predictionDeadlineElapsed", 0).put("predictionUntilElapsed", 0)
                    .put("status", "Camino al transbordo");
            capture(session, engine(session), SystemClock.elapsedRealtime());
            persist(session);
            return session;
        }
        return waitForNext(session) ? session : null;
    }

    boolean awaitNext(String token) throws Exception {
        JSONObject session = load();
        if (session == null || !token.equals(session.optString("token")) || !RIDING.equals(session.optString("phase"))) return false;
        return waitForNext(session);
    }

    private boolean waitForNext(JSONObject session) throws Exception {
        JSONArray legs = session.optJSONArray("legs"); int next = session.optInt("legIndex") + 1;
        if (legs == null || next >= legs.length()) return false;
        session.put("phase", WAITING).put("legIndex", next).put("ringing", false).put("forceAlarm", false).put("transferAcknowledged", false)
                .put("token", UUID.randomUUID().toString())
                .put("status", "Transbordo: confirma la salida al subir al siguiente tren");
        persist(session); return true;
    }
    boolean save(JSONObject session) {
        JSONObject current = load();
        if (current == null || !current.optString("token").equals(session.optString("token"))) return false;
        return prefs.edit().putString("active", session.toString()).commit();
    }
    private void persist(JSONObject session) { if (!prefs.edit().putString("active", session.toString()).commit()) throw new IllegalStateException("No se pudo guardar el viaje"); }
    @android.annotation.SuppressLint("ApplySharedPref")
    void clear() { prefs.edit().remove("active").commit(); }
    static List<Station> stations(JSONObject session) throws Exception {
        JSONArray values = session.getJSONArray("stations"); List<Station> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) { JSONObject s = values.getJSONObject(i); result.add(new Station(s.getString("id"), s.getString("name"), s.getDouble("lat"), s.getDouble("lon"))); }
        return result;
    }
    static List<Station> upcomingStations(JSONObject session) throws Exception {
        JSONObject leg = session.getJSONArray("legs").getJSONObject(session.getInt("legIndex"));
        return stations(leg);
    }

    static String destinationName(JSONObject session) throws JSONException {
        if (needsOrigin(session)) return session.optString("destinationName");
        JSONArray legs = session.getJSONArray("legs");
        JSONArray stops = legs.getJSONObject(legs.length() - 1).getJSONArray("stations");
        return stops.getJSONObject(stops.length() - 1).getString("name");
    }

    static boolean needsOrigin(JSONObject session) { return session.optJSONArray("legs") == null; }

    static Station interchange(JSONObject session) throws Exception {
        List<Station> stops = stations(session);
        return stops.get(stops.size() - 1);
    }

    static boolean hasTransfer(JSONObject session) {
        JSONArray legs = session.optJSONArray("legs");
        return legs != null && session.optInt("legIndex") + 1 < legs.length();
    }

    static boolean silentTransfer(JSONObject session) {
        return hasTransfer(session) && (!session.optBoolean("transferAlarm", true) || session.optBoolean("transferAcknowledged"));
    }

    static JourneyEngine engine(JSONObject session) throws Exception {
        JourneyEngine engine = new JourneyEngine(stations(session), CatalogCodec.ints(session.getJSONArray("segments")), session.getInt("advance"),
                session.getLong("startedElapsed"), session.getInt("confirmed"), session.getLong("anchorElapsed"), session.optBoolean("transferAcknowledged"));
        engine.restorePosition(session.optDouble("position", session.optInt("confirmed")), session.optLong("lastGpsElapsed", -1),
                session.optDouble("pace", 1), session.optInt("calibrationIndex", -1), session.optLong("calibrationElapsed"), session.optBoolean("reachedAlert"));
        engine.prediction(session.optLong("predictionDeadlineElapsed"), session.optLong("predictionUntilElapsed")); return engine;
    }
    private static String gpsState(JSONObject session) { return "Buscando señal precisa"; }
    static void capture(JSONObject session, JourneyEngine engine, long now) throws JSONException {
        session.put("confirmed", engine.confirmedIndex()).put("anchorElapsed", engine.anchorAt()).put("deadlineElapsed", engine.deadline(now))
                .put("position", engine.position()).put("lastGpsElapsed", engine.lastGpsAt()).put("pace", engine.pace())
                .put("calibrationIndex", engine.calibrationIndex()).put("calibrationElapsed", engine.calibrationAt()).put("reachedAlert", engine.reachedAlert())
                .put("predictionDeadlineElapsed", engine.predictionDeadline()).put("predictionUntilElapsed", engine.predictionUntil());
    }
    static TransitLine.Departure associatedTrip(JSONObject session) throws Exception {
        JSONObject trip = session.optJSONObject("trip");
        if (!session.optBoolean("autoAssociated") || trip == null) return null;
        TransitLine.ScheduledTrip scheduled = CatalogCodec.decodeTrip(trip);
        String date = session.getString("serviceDate");
        return new TransitLine.Departure(scheduled, date, scheduled.timeAt(stations(session).get(0).id, date));
    }
    static void associate(JSONObject session, TransitLine.Departure trip) throws JSONException {
        session.put("tripId", trip == null ? "" : trip.trip.id).put("serviceDate", trip == null ? "" : trip.date)
                .put("trip", trip == null ? JSONObject.NULL : CatalogCodec.encodeTrip(trip.trip)).put("autoAssociated", trip != null);
    }
    static RealtimeContext realtimeContext(JSONObject session) throws Exception {
        Set<String> stops = new HashSet<>(); List<Station> stations = stations(session); for (Station s : stations) stops.add(s.id);
        JSONObject trip = session.optJSONObject("trip");
        return new RealtimeContext(trip == null ? null : CatalogCodec.decodeTrip(trip), session.optString("serviceDate"), engine(session).alertStation().id,
                stations.get(stations.size() - 1).id, new HashSet<>(CatalogCodec.strings(session.optJSONArray("routeIds"))),
                new HashSet<>(CatalogCodec.strings(session.optJSONArray("agencyIds"))), stops);
    }
    static long wakeAt(JSONObject session, long now) {
        long deadline = session.optLong("deadlineElapsed");
        if (session.optBoolean("forceAlarm")) return now + 1_000;
        long expiry = session.optLong("predictionUntilElapsed");
        return expiry > now ? Math.min(deadline, expiry + 1000) : deadline;
    }
}
