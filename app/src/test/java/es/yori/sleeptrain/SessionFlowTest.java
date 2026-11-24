package es.yori.sleeptrain;

import android.content.*;
import android.location.Location;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import es.yori.sleeptrain.data.TransitRepository;
import es.yori.sleeptrain.domain.*;
import es.yori.sleeptrain.core.JourneyEngine;
import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.core.StationMatcher;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.time.Duration;
import java.util.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class SessionFlowTest {
    private Context context;
    private SessionStore store;
    private JourneyPlan plan;
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication(); context.getSharedPreferences("journey", 0).edit().clear().commit();
        context.getSharedPreferences("preferences", 0).edit().clear().commit();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 1);
        store = new SessionStore(context);
        plan = new JourneyPlanner(new TransitRepository(context).load()).transfer(TransitLine.METRO, "par_4_48", "35607", 1, "Embajadores");
    }
    private JSONObject create() throws Exception { return store.create(plan, true); }
    @Test public void journeyKeepsGpsEnabledAndArmsBackupWithoutLocationPermission() throws Exception {
        JSONObject session = create(); assertTrue(session.getBoolean("gps")); assertEquals(SessionStore.RIDING, session.getString("phase"));
        assertEquals(TransitLine.METRO, session.getString("lineId")); assertTrue(session.getLong("deadlineElapsed") > SystemClock.elapsedRealtime());
        AlarmScheduler.arm(context, session);
    }
    @Test public void acknowledgingFirstAlarmWaitsForManualBoardingBeforeNextLeg() throws Exception {
        JSONObject first = create(); first.put("ringing", true); store.save(first); JourneyActions.acknowledge(context);
        JSONObject waiting = store.load(); assertEquals(SessionStore.WAITING, waiting.getString("phase")); assertEquals(1, waiting.getInt("legIndex"));
        JSONObject second = store.continueJourney(); assertEquals(TransitLine.RENFE, second.getString("lineId"));
        assertEquals(0, second.getInt("confirmed")); assertNotEquals(first.getString("token"), second.getString("token")); assertFalse(store.save(first));
    }
    @Test public void oldBackupCannotRingAfterBoardingSecondTrain() throws Exception {
        JSONObject first = create(); first.put("ringing", true); store.save(first); JourneyActions.acknowledge(context); JSONObject second = store.continueJourney();
        new BackupAlarmReceiver().onReceive(context, new Intent().setAction("ALARM").putExtra("token", first.getString("token")));
        assertEquals(second.getString("token"), store.load().getString("token")); assertFalse(store.load().getBoolean("ringing"));
    }
    @Test public void delayedAlarmRequestCannotRingDuringTransferWait() throws Exception {
        JSONObject first = create(); first.put("ringing", true); store.save(first); JourneyActions.acknowledge(context);
        AlarmService.ring(context, first.getString("token"), "Aviso antiguo");
        assertEquals(SessionStore.WAITING, store.load().getString("phase")); assertFalse(store.load().getBoolean("forceAlarm"));
    }
    @Test public void queuedPlaybackServiceCannotRestartAnAcknowledgedLeg() throws Exception {
        JSONObject first = create(); first.put("ringing", true); store.save(first); JourneyActions.acknowledge(context);
        Intent old = new Intent(context, AlarmService.class).putExtra("token", first.getString("token"));
        org.robolectric.android.controller.ServiceController<AlarmService> controller = Robolectric.buildService(AlarmService.class, old).create();
        try {
            assertEquals(android.app.Service.START_NOT_STICKY, controller.get().onStartCommand(old, 0, 1));
            assertFalse(store.load().getBoolean("ringing"));
        } finally { controller.destroy(); }
    }
    @Test public void finalAcknowledgementCancelsEntireJourney() throws Exception {
        JSONObject first = create(); first.put("ringing", true); store.save(first); JourneyActions.acknowledge(context);
        JSONObject second = store.continueJourney(); second.put("ringing", true); store.save(second); JourneyActions.acknowledge(context);
        assertNull(store.load());
    }
    @Test public void expiredPredictionCannotKeepPostponingAnOverdueBackup() throws Exception {
        JSONObject session = create(); long now = SystemClock.elapsedRealtime();
        session.put("predictionDeadlineElapsed", now + 600_000).put("predictionUntilElapsed", now + 60_000).put("deadlineElapsed", now + 600_000); store.save(session);
        assertEquals(now + 61_000, SessionStore.wakeAt(session, now));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(Math.max(61_000, SessionStore.engine(session).deadline() - now + 1)));
        new BackupAlarmReceiver().onReceive(context, new Intent().setAction("ALARM").putExtra("token", session.getString("token")));
        JSONObject current = store.load(); assertEquals(SessionStore.engine(current).deadline(), current.getLong("deadlineElapsed"));
        assertTrue(current.getBoolean("forceAlarm")); // The playback service has been requested; no Activity is needed.
    }
    @Test public void restartNeverReusesAnOldJourney() throws Exception {
        create(); Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 2); assertNull(store.load());
    }
    @Test public void activityShowsUnifiedDestinationAndRecurringAlarmEditor() throws Exception {
        try (org.robolectric.android.controller.ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get(); View root = activity.findViewById(android.R.id.content);
            waitForEnabledButton(root, "+ Nueva").performClick();
            String screen = texts(root);
            assertTrue(screen.contains("Nueva alarma"));
            assertTrue(screen.contains("07:00")); assertTrue(screen.contains("14:00"));
            assertTrue(screen.contains("Lunes a viernes")); assertTrue(screen.contains("Etiqueta (opcional)"));
            assertNull(findButton(root, "Metro")); assertNull(findButton(root, "Transbordo"));
        }
    }
    @Test public void gpsPositionAndPaceSurviveProcessRecreation() throws Exception {
        JSONObject session = create(); long now = SystemClock.elapsedRealtime();
        JourneyEngine engine = SessionStore.engine(session);
        assertTrue(engine.correctPosition(.5, -1, now, now));
        SessionStore.capture(session, engine, now); store.save(session);
        JourneyEngine restored = SessionStore.engine(new SessionStore(context).load());
        assertEquals(.5, restored.position(), .001); assertEquals(now, restored.lastGpsAt());
        assertEquals(engine.deadline(now), restored.deadline(now)); assertTrue(restored.gpsFresh(now));
    }
    @Test public void previouslyScheduledBackupUsesGpsCorrectionInsteadOfOldDeadline() throws Exception {
        JSONObject session = create(); long originalDeadline = session.getLong("deadlineElapsed");
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(80)); long now = SystemClock.elapsedRealtime();
        JourneyEngine engine = SessionStore.engine(session); assertTrue(engine.correctPosition(0, 0, now, now));
        SessionStore.capture(session, engine, now); store.save(session);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(Math.max(1, originalDeadline - now + 1)));
        new BackupAlarmReceiver().onReceive(context, new Intent().setAction("ALARM").putExtra("token", session.getString("token")));
        assertFalse(store.load().getBoolean("forceAlarm")); assertTrue(store.load().getLong("deadlineElapsed") > originalDeadline);
    }
    @Test public void startingNextLegClearsGpsCalibrationAndPosition() throws Exception {
        JSONObject session = create(); long now = SystemClock.elapsedRealtime();
        JourneyEngine engine = SessionStore.engine(session); engine.correctPosition(.5, -1, now, now);
        SessionStore.capture(session, engine, now); session.put("ringing", true); store.save(session);
        JourneyActions.acknowledge(context); JSONObject next = store.continueJourney();
        assertEquals(-1, next.getLong("lastGpsElapsed")); assertEquals(0, next.getDouble("position"), .001);
        assertEquals(-1, next.getInt("calibrationIndex")); assertEquals(1, next.getDouble("pace"), .001);
    }
    @Test public void activeTripShowsGpsProgressInTheUnifiedScreen() throws Exception {
        JSONObject session = create();
        long now = SystemClock.elapsedRealtime();
        JourneyEngine engine = SessionStore.engine(session);
        engine.correctPosition(.5, -1, now, now);
        SessionStore.capture(session, engine, now); store.save(session);
        try (org.robolectric.android.controller.ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            assertTrue(texts(root).contains("VIAJE EN CURSO"));
            assertTrue(texts(root).contains("GPS · "));
            assertNull(findButton(root, "Corregir mi posición con GPS"));
        }
    }

    @Test public void unifiedTransferRingsEarlyAndResumesTrackingBeforeBoarding() throws Exception {
        context.getSharedPreferences("preferences", 0).edit().putInt(SessionStore.ADVANCE_PREFERENCE, 2).apply();
        JSONObject first = store.createAutomatic(plan, true, "custom-audio", "35607", "routine-1", null);
        assertEquals(2, first.getInt("advance"));
        assertEquals("Embajadores", SessionStore.interchange(first).name);
        assertNotEquals("Embajadores", SessionStore.engine(first).alertStation().name);
        expire(first);
        first = store.load();
        assertTrue(first.getBoolean("forceAlarm"));
        first.put("ringing", true); store.save(first);
        JourneyActions.acknowledge(context);
        JSONObject approaching = store.load();
        assertEquals(SessionStore.RIDING, approaching.getString("phase"));
        assertEquals(0, approaching.getInt("legIndex"));
        assertEquals("Embajadores", SessionStore.engine(approaching).alertStation().name);
        assertTrue(approaching.getBoolean("transferAcknowledged"));
        assertFalse(approaching.getBoolean("ringing"));
        assertFalse(store.save(first));
        assertThrows(IllegalStateException.class, () -> store.continueJourney());
        expire(approaching);
        assertEquals(SessionStore.WAITING, store.load().getString("phase"));
        assertFalse(store.load().getBoolean("forceAlarm"));
        JSONObject second = store.continueJourney();
        assertEquals("custom-audio", second.getString("audioId"));
        assertEquals("routine-1", second.getString("profileId"));
        assertEquals("Móstoles", SessionStore.engine(second).alertStation().name);
    }

    @Test public void scheduledTransfersUseTheGlobalAdvanceWithoutChangingTheFinalAlarm() throws Exception {
        var preferences = context.getSharedPreferences("preferences", 0);
        CommuteProfile profile = new CommuteProfile("", "par_4_48", "35607", 420, -1, CommuteProfile.DAYS_EVERY_DAY,
                "", "", true, true);
        CommuteScheduler.Occurrence occurrence = CommuteScheduler.next(profile, java.time.Instant.EPOCH, TransitLine.ZONE);
        for (int advance = 0; advance <= 3; advance++) {
            preferences.edit().putInt(SessionStore.ADVANCE_PREFERENCE, advance).apply();
            JSONObject session = store.createScheduled(plan, profile, occurrence, null);
            assertEquals(Math.min(advance, plan.legs.get(0).stations.size() - 1), session.getInt("advance"));
            assertEquals(1, session.getJSONArray("legs").getJSONObject(1).getInt("advance"));
            store.clear();
        }
    }

    @Test public void aChosenJourneyWaitsForGpsAndCanBeReplacedAfterADivergence() throws Exception {
        JSONObject waiting = store.createManual(plan, true, "custom-audio", "35607", "Móstoles-El Soto", 1, null, true);
        String token = waiting.getString("token");
        assertEquals(SessionStore.WAITING_LOCATION, waiting.getString("phase"));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
        new BackupAlarmReceiver().onReceive(context, new Intent().setAction("ALARM").putExtra("token", token));
        assertEquals(SessionStore.WAITING_LOCATION, store.load().getString("phase"));
        assertFalse(store.load().optBoolean("forceAlarm"));
        StationMatcher matcher = new StationMatcher();
        Station origin = plan.legs.get(0).stations.get(0);
        assertNull(store.startPendingLocation(token, gps(origin, 140), matcher, null));
        JSONObject started = store.startPendingLocation(token, gps(origin, 10), matcher, null);
        assertNotNull(started);
        assertEquals(SessionStore.RIDING, started.getString("phase"));
        assertNotEquals(token, started.getString("token"));
        assertEquals(plan.legs.get(0).line.id, started.getString("lineId"));
        JourneyPlan alternative = new JourneyPlanner(new TransitRepository(context).load()).direct(TransitLine.RENFE, "35609", "35607", 1);
        Station newOrigin = alternative.legs.get(0).stations.get(0);
        started.put("lockedRoute", true); // Sessions saved by the previous APK can also be rerouted.
        store.save(started);
        assertThrows(IllegalArgumentException.class, () -> store.replaceRoute(started, alternative, gps(newOrigin, 140)));
        JSONObject revised = store.replaceRoute(started, alternative, gps(newOrigin, 10));
        assertEquals(TransitLine.RENFE, revised.getString("lineId"));
        assertEquals("35607", revised.getString("destinationId"));
        assertEquals("custom-audio", revised.getString("audioId"));
        assertNotEquals(started.getString("token"), revised.getString("token"));
        assertFalse(store.save(started));
    }

    @Test public void quickStartCanBePreparedWithoutAnOriginOrAnItinerary() throws Exception {
        JSONObject waiting = store.createManual(null, false, "", "35607", "Móstoles-El Soto", 2, null, false);
        assertEquals(SessionStore.WAITING_LOCATION, waiting.getString("phase"));
        assertEquals("Móstoles-El Soto", SessionStore.destinationName(waiting));
        assertTrue(SessionStore.needsOrigin(waiting));
        String token = waiting.getString("token");
        Station origin = plan.legs.get(0).stations.get(0);
        assertNull(store.startPendingLocation(token, gps(origin, 10), new StationMatcher(), null));
        JSONObject started = store.startPendingLocation(token, gps(origin, 10), new StationMatcher(), plan);
        assertEquals(SessionStore.RIDING, started.getString("phase"));
        assertFalse(SessionStore.needsOrigin(started));
        assertFalse(started.getBoolean("vibration"));
        assertFalse(started.getBoolean("transferAlarm"));
        assertNotEquals(token, started.getString("token"));
    }

    @Test public void routeCorrectionWaitsForForwardMovementOnAnAlternative() {
        Station a = new Station("a", "A", 40, -4), b = new Station("b", "B", 40.004, -4);
        Station c = new Station("c", "C", 40.010, -4);
        TransitLine line = new TransitLine("METRO_alt", "Metro · L2", Arrays.asList(a, b, c), new int[]{60, 60},
                Collections.emptyList(), Collections.emptySet(), Collections.emptySet(), "", "", "", "");
        AutomaticRoutePlanner planner = new AutomaticRoutePlanner(Collections.singletonMap(line.id, line));
        RouteCorrection correction = new RouteCorrection();
        assertNull(correction.observe(planner, c.id, 1, gps(a, 10), SystemClock.elapsedRealtime()));
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(15));
        JourneyPlan updated = correction.observe(planner, c.id, 1, gps(b, 10), SystemClock.elapsedRealtime());
        assertNotNull(updated);
        assertEquals(a.id, updated.legs.get(0).origin);
        assertEquals(c.id, updated.legs.get(0).destination);
    }

    @Test public void silentTransferDoesNotRingAndStillArmsTheDestinationAlarm() throws Exception {
        context.getSharedPreferences("preferences", 0).edit().putInt(SessionStore.ADVANCE_PREFERENCE, 3).apply();
        JSONObject first = store.createManual(plan, true, "", "35607", "Móstoles-El Soto", 1,
                gps(plan.legs.get(0).stations.get(0), 10), false);
        assertEquals("Embajadores", SessionStore.engine(first).alertStation().name);
        expire(first);
        JSONObject waiting = store.load();
        assertEquals(SessionStore.WAITING, waiting.getString("phase"));
        assertFalse(waiting.getBoolean("ringing"));
        assertFalse(waiting.getBoolean("forceAlarm"));
        assertFalse(store.save(first));
        JSONObject finalLeg = store.continueJourney();
        expire(finalLeg);
        assertTrue(store.load().getBoolean("forceAlarm"));
    }

    @Test public void routineSwitchAndSerializationRetainBothRouteChoicesAndAlarmPreference() throws Exception {
        CommuteProfile selected = new CommuteProfile("", "out", "back", 420, 840, 31,
                "Trabajo", "", true, true, false, "outbound-route", "return-route");
        CommuteProfile restored = CommuteProfile.fromJson(selected.withEnabled(false).toJson());
        assertFalse(restored.enabled);
        assertFalse(restored.transferAlarm);
        assertEquals("outbound-route", restored.route(false));
        assertEquals("return-route", restored.route(true));
        JSONObject historical = selected.toJson();
        historical.remove("transferAlarm");
        historical.remove("outboundRoute");
        assertTrue(CommuteProfile.fromJson(historical).transferAlarm);
        assertEquals("", CommuteProfile.fromJson(historical).route(false));
    }

    private void expire(JSONObject session) throws Exception {
        long now = SystemClock.elapsedRealtime();
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(session.getLong("deadlineElapsed") - now + 1));
        new BackupAlarmReceiver().onReceive(context, new Intent().setAction("ALARM").putExtra("token", session.getString("token")));
    }

    private static Location gps(Station station, float accuracy) {
        Location location = new Location("gps");
        location.setLatitude(station.latitude);
        location.setLongitude(station.longitude);
        location.setAccuracy(accuracy);
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return location;
    }
    @Test public void reopeningActiveJourneyRequestsTrackingAgain() throws Exception {
        create();
        try (org.robolectric.android.controller.ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            Intent started = shadowOf((android.app.Application) context).getNextStartedService();
            assertNotNull(started); assertEquals(TripService.class.getName(), started.getComponent().getClassName());
        }
    }
    @Test public void legacySessionAndAudioSettingsCannotDisableGpsOrAutomaticOutput() throws Exception {
        JSONObject session = create(); session.put("gps", false).put("audioMode", "headphones").put("speakerFallback", false); store.save(session);
        JSONObject loaded = store.load(); assertTrue(loaded.getBoolean("gps")); assertEquals("auto", loaded.getString("audioMode")); assertTrue(loaded.getBoolean("speakerFallback"));
        loaded.put("ringing", true); store.save(loaded); JourneyActions.acknowledge(context);
        JSONObject next = store.continueJourney(); assertTrue(next.getBoolean("gps")); assertEquals("auto", next.getString("audioMode"));
        assertTrue(next.getBoolean("speakerFallback")); assertFalse(next.getBoolean("autoAssociated")); assertEquals("", next.getString("tripId"));
    }
    @Test public void automaticAssociationPersistsAndClearsWithGpsContradiction() throws Exception {
        Map<String, TransitLine> lines = new TransitRepository(context).load();
        JSONObject session = store.create(new JourneyPlanner(lines).direct(TransitLine.RENFE, "18000", "35607", 1), true);
        TransitLine line = lines.get(TransitLine.RENFE);
        TransitLine.ScheduledTrip trip = line.trips.values().stream().filter(t -> t.indexes.containsKey("18000")
                && t.indexes.containsKey("35607") && t.indexes.get("18000") < t.indexes.get("35607")).findFirst().get();
        String date = trip.dates.iterator().next();
        SessionStore.associate(session, new TransitLine.Departure(trip, date, 0)); store.save(session);
        assertEquals(trip.id, SessionStore.associatedTrip(store.load()).trip.id);
        SessionStore.associate(session, null); store.save(session);
        assertNull(SessionStore.associatedTrip(store.load())); assertEquals("", store.load().getString("tripId"));
    }
    private static Button waitForEnabledButton(View root, String label) throws Exception {
        long until = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle();
            Button view = findButton(root, label);
            if (view != null && view.isEnabled()) return view;
            Thread.sleep(10);
        }
        throw new AssertionError("Botón no disponible: " + label + "\n" + texts(root));
    }
    private static String texts(View root) {
        StringBuilder result = new StringBuilder(); if (root instanceof TextView) result.append(((TextView) root).getText()).append('\n');
        if (root instanceof ViewGroup) { ViewGroup group = (ViewGroup) root; for (int i = 0; i < group.getChildCount(); i++) result.append(texts(group.getChildAt(i))); } return result.toString();
    }
    private static Button findButton(View root, String label) {
        if (root instanceof Button && ((Button) root).getText().toString().equals(label)) return (Button) root;
        if (root instanceof ViewGroup) { ViewGroup group = (ViewGroup) root; for (int i = 0; i < group.getChildCount(); i++) { Button result = findButton(group.getChildAt(i), label); if (result != null) return result; } } return null;
    }
}
