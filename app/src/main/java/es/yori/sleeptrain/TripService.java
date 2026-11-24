package es.yori.sleeptrain;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.location.*;
import android.os.*;
import es.yori.sleeptrain.core.*;
import es.yori.sleeptrain.domain.*;
import es.yori.sleeptrain.data.realtime.*;
import es.yori.sleeptrain.data.TransitRepository;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Foreground fusion of device fixes, downloaded route data and operator observations. */
public final class TripService extends Service implements LocationListener {
    public static final String CONFIRM = "CONFIRM_STATION";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private StationMatcher matcher = new StationMatcher();
    private final RouteCorrection routeCorrection = new RouteCorrection();
    private volatile AutomaticRoutePlanner routePlanner;
    private CommuteDetection detection;
    private MovementEvidence transferMovement;
    private List<Station> expectedTransferStops;
    private Location pendingFix;
    private ScheduledExecutorService worker;
    private JSONObject session;
    private JourneyEngine engine;
    private LocationManager locations;
    private long lastObservation;
    private final AtomicBoolean resetAssociation = new AtomicBoolean();
    private final AtomicReference<AutoTrainMatcher.DeviceFix> deviceFix = new AtomicReference<>();
    private final BroadcastReceiver providerChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { configureLocation(); if (engine != null) publish(); }
    };
    private boolean listeningForProviders;
    private String source = "Estimación local";
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (session == null) return;
            if (engine.lastGpsAt() >= 0 && !engine.gpsFresh(SystemClock.elapsedRealtime()) && "GPS".equals(session.optString("positionSource")))
                source = "Sin señal reciente · estimación desde la última ubicación";
            else if (SystemClock.elapsedRealtime() - lastObservation > 60_000) source = "Sin posición confirmada reciente · estimación";
            publish(); if (session != null) handler.postDelayed(this, 5_000);
        }
    };
    @Override public void onCreate() { super.onCreate(); locations = getSystemService(LocationManager.class); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        JSONObject saved = new SessionStore(this).load();
        if (CommuteScheduler.isDetection(intent)) {
            if (saved != null && session != null) return START_STICKY;
            if (saved != null) { stopSelf(); return START_NOT_STICKY; }
            if (detection == null) detection = new CommuteDetection(this, id -> onStartCommand(null, 0, id));
            return detection.start(intent, startId);
        }
        if (saved == null || saved.optBoolean("ringing") || saved.optBoolean("forceAlarm")) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (detection != null) { detection.close(); detection = null; }
        String phase = saved.optString("phase", SessionStore.RIDING);
        if (session != null && session.optString("token").equals(saved.optString("token"))
                && session.optString("phase").equals(saved.optString("phase"))) {
            configureLocation();
            if (SessionStore.RIDING.equals(phase)) manual(intent);
            return START_STICKY;
        }
        resetTracking();
        if (SessionStore.WAITING.equals(phase) || SessionStore.WAITING_LOCATION.equals(phase)) return startLocationWatch(saved);
        if (!SessionStore.RIDING.equals(phase)) {
            stopSelf(); return START_NOT_STICKY;
        }
        try {
            session = saved; engine = SessionStore.engine(saved); source = saved.optString("status", "Estimación local");
            boolean gps = locationAllowed();
            int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
            if (gps && Build.VERSION.SDK_INT >= 29) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            Notification notification = Notifications.build(this, false, "SlipTrain · viaje activo", "Comprobando datos del transporte", saved.optString("token"));
            if (Build.VERSION.SDK_INT >= 29) startForeground(10, notification, type); else startForeground(10, notification);
            watchLocationProviders();
            configureLocation();
            final String token = saved.getString("token"), lineId = saved.optString("lineId", TransitLine.RENFE);
            final RealtimeContext context = SessionStore.realtimeContext(saved).withTrip(null);
            final AutoTrainMatcher autoMatcher = new AutoTrainMatcher(SessionStore.associatedTrip(saved));
            final TransitLine[] automaticLine = new TransitLine[1];
            final boolean realTime = saved.optBoolean("realTime", TransitLine.RENFE.equals(lineId));
            if (!saved.optBoolean("autoAssociated")) { SessionStore.associate(session, null); engine.prediction(0, 0); }
            List<Station> stations = SessionStore.stations(saved);
            final String metroStop = knownMetro(stations.get(stations.size() - 1).id) ? stations.get(stations.size() - 1).id : stations.get(0).id;
            final String direction = saved.optString("direction");
            RenfeRealtimeRepository renfe = new RenfeRealtimeRepository(); MetroArrivalsRepository metro = new MetroArrivalsRepository();
            worker = Executors.newSingleThreadScheduledExecutor();
            if (!saved.optString("destinationId").isEmpty()) loadRoutePlanner(token);
            worker.scheduleWithFixedDelay(() -> {
                if (realTime) {
                    try {
                        if (automaticLine[0] == null) automaticLine[0] = new TransitRepository(this).loadRealtimeLine(lineId);
                        renfe.refresh();
                        if (resetAssociation.getAndSet(false)) autoMatcher.reset();
                        AutoTrainMatcher.DeviceFix fix = deviceFix.get();
                        TransitLine.Departure selected = autoMatcher.match(automaticLine[0], stations, renfe.positions, fix,
                                System.currentTimeMillis(), SystemClock.elapsedRealtime());
                        RealtimeSnapshot snapshot = renfe.interpret(context.withTrip(selected), System.currentTimeMillis());
                        snapshot.automaticAssociation = true; snapshot.associatedTrip = selected;
                        if (fix != null) { snapshot.associationGpsElapsed = fix.elapsed; snapshot.associationPosition = fix.position; }
                        handler.post(() -> receive(token, snapshot));
                    } catch (Exception ignored) {
                        // Retry next poll; downloaded route and GPS continue independently of the operator.
                    }
                } else if (TransitLine.METRO.equals(lineId) && knownMetro(metroStop)) {
                    String board = metro.load(metroStop, direction);
                    handler.post(() -> {
                        if (!current(token)) return;
                        try { session.put("realtimeInfo", board); publish(); } catch (Exception ignored) { /* Backup remains. */ }
                    });
                }
            }, 0, 20, TimeUnit.SECONDS);
            manual(intent); handler.post(heartbeat); return START_STICKY;
        } catch (Exception error) {
            try { saved.put("status", "Seguimiento no disponible · respaldo local armado"); new SessionStore(this).save(saved); } catch (Exception ignored) { }
            stopSelf(); return START_NOT_STICKY;
        }
    }
    private void watchLocationProviders() {
        if (listeningForProviders) return;
        IntentFilter filter = new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(providerChanges, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(providerChanges, filter);
        listeningForProviders = true;
    }

    private int startLocationWatch(JSONObject saved) {
        if (saved.optString("destinationId").isEmpty()) { stopSelf(); return START_NOT_STICKY; }
        try {
            session = saved;
            boolean transfer = SessionStore.WAITING.equals(saved.optString("phase"));
            if (transfer) {
                expectedTransferStops = SessionStore.upcomingStations(saved);
                transferMovement = new MovementEvidence(expectedTransferStops);
            }
            loadRoutePlanner(saved.optString("token"));
            int type = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
            if (locationAllowed() && Build.VERSION.SDK_INT >= 29) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            Notification notification = locationNotification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(10, notification, type);
            else startForeground(10, notification);
            watchLocationProviders();
            configureLocation();
            return START_STICKY;
        } catch (Exception denied) {
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void loadRoutePlanner(String token) {
        if (routePlanner != null) return;
        if (worker == null) worker = Executors.newSingleThreadScheduledExecutor();
        worker.execute(() -> {
            try {
                AutomaticRoutePlanner loaded = new AutomaticRoutePlanner(new TransitRepository(this).load());
                handler.post(() -> {
                    if (session == null || !token.equals(session.optString("token"))) return;
                    routePlanner = loaded;
                    if (pendingFix != null) {
                        Location location = pendingFix;
                        pendingFix = null;
                        onLocationChanged(location);
                    }
                });
            } catch (Exception ignored) { /* A saved itinerary can still be tracked locally. */ }
        });
    }

    private Notification locationNotification() {
        if (SessionStore.WAITING_LOCATION.equals(session.optString("phase"))) {
            return tripNotification("Aviso preparado · buscando ubicación", "El seguimiento empezará cuando vuelva la señal");
        }
        if (transferMovement != null) {
            return tripNotification("Esperando el siguiente tren", "El aviso se reactivará al detectar que avanzas");
        }
        return tripNotification("SlipTrain · viaje activo", "Buscando señal precisa");
    }

    private Notification tripNotification(String title, String body) {
        return Notifications.build(this, false, title, body, session.optString("token"));
    }

    private void resetTracking() {
        handler.removeCallbacks(heartbeat);
        if (worker != null) { worker.shutdownNow(); worker = null; }
        if (locations != null) {
            try { locations.removeUpdates(this); } catch (SecurityException ignored) { }
        }
        matcher = new StationMatcher();
        routeCorrection.reset();
        transferMovement = null;
        expectedTransferStops = null;
        pendingFix = null;
        deviceFix.set(null);
        resetAssociation.set(false);
        lastObservation = SystemClock.elapsedRealtime();
        session = null;
        engine = null;
    }

    private static boolean knownMetro(String id) { return "par_4_46".equals(id) || "par_4_273".equals(id); }
    private boolean current(String token) {
        JSONObject current = new SessionStore(this).load();
        return session != null && current != null && token.equals(current.optString("token"))
                && SessionStore.RIDING.equals(current.optString("phase", SessionStore.RIDING)) && !current.optBoolean("ringing");
    }
    private void receive(String token, RealtimeSnapshot result) {
        if (!current(token)) return;
        try {
            long elapsed = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
            AutoTrainMatcher.DeviceFix latest = deviceFix.get();
            if (latest != null && latest.elapsed > result.associationGpsElapsed
                    && Math.abs(latest.position - result.associationPosition) > .15) {
                resetAssociation.set(true); return;
            }
            if (result.automaticAssociation) {
                String id = result.associatedTrip == null ? "" : result.associatedTrip.trip.id;
                if (!id.equals(session.optString("tripId"))) engine.prediction(0, 0);
                SessionStore.associate(session, result.associatedTrip);
            }
            if (result.hasFreshVehicle) {
                if (engine.observeRemote(result.stopId, result.vehicleStatus, result.measuredAt, wall, elapsed)) {
                    source = "Renfe · tren enlazado automáticamente"; lastObservation = elapsed;
                    session.put("positionSource", "RENFE");
                }
            }
            if (!engine.gpsFresh(elapsed) && result.predictionAt > 0 && result.predictionExpiresAt > wall
                    && (engine.lastGpsAt() < 0 || elapsed + result.predictionExpiresAt - wall - JourneyEngine.REMOTE_MAX_AGE_MS > engine.lastGpsAt())) {
                long margin = session.optBoolean("transferAcknowledged") ? 0 : 30_000;
                engine.prediction(elapsed + Math.max(1_000, result.predictionAt - wall - margin), elapsed + result.predictionExpiresAt - wall);
            } else engine.prediction(0, 0);
            String info = result.predictionAt > 0 ? "Llegada prevista al aviso: " + java.time.Instant.ofEpochMilli(result.predictionAt).atZone(TransitLine.ZONE)
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) : "Sin previsión reciente para la parada del aviso";
            if (result.delaySeconds != null) info += " · desfase " + Math.round(result.delaySeconds / 60f) + " min";
            if (session.optString("tripId").isEmpty()) info = "Seguimiento automático por GPS y recorrido local";
            if (!result.critical.isEmpty() && !result.alerts.contains(result.critical)) result.alerts.add(0, result.critical);
            session.put("realtimeInfo", info).put("alerts", String.join("\n", result.alerts));
            if (!SessionStore.silentTransfer(session) && engine.shouldAlertForIncident(result.critical, elapsed)) {
                new SessionStore(this).save(session); AlarmService.ring(this, token, result.critical); stopSelf(); return;
            }
            publish();
        } catch (Exception ignored) { /* Keep exact backup; malformed data never imply progress. */ }
    }
    private void manual(Intent intent) {
        if (intent == null || !CONFIRM.equals(intent.getAction())) return;
        if (session == null || !session.optString("token").equals(intent.getStringExtra("token"))) return;
        int index = intent.getIntExtra("index", -1);
        if (engine != null && index >= 0) { engine.confirmStation(index, SystemClock.elapsedRealtime()); lastObservation = SystemClock.elapsedRealtime(); source = "Estación confirmada por ti"; try { session.put("positionSource", "MANUAL"); } catch (Exception ignored) { } publish(); }
    }
    private boolean locationAllowed() {
        boolean permission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean enabled = Build.VERSION.SDK_INT >= 28 ? locations.isLocationEnabled() : locations.isProviderEnabled(LocationManager.GPS_PROVIDER) || locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        return permission && enabled;
    }
    private void configureLocation() {
        if (session == null) return;
        try {
            if (!locationAllowed()) {
                session.put("gpsState", "Ubicación desactivada o sin permiso · usando estimación");
                locations.removeUpdates(this); return;
            }
            if (Build.VERSION.SDK_INT >= 29) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                if (Build.VERSION.SDK_INT >= 34) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                startForeground(10, locationNotification(), type);
            }
            session.put("gpsState", checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    ? "Buscando señal precisa" : "Permiso aproximado · activa ubicación precisa para corregir");
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) requestProvider(LocationManager.GPS_PROVIDER);
            requestProvider(LocationManager.NETWORK_PROVIDER);
            if (Build.VERSION.SDK_INT >= 31) requestProvider(LocationManager.FUSED_PROVIDER);
        } catch (RuntimeException | JSONException error) {
            try { session.put("gpsState", "No se pudo activar ubicación · abre la app y revisa permisos"); } catch (Exception ignored) { }
        }
    }
    private void requestProvider(String provider) {
        try {
            if (locations.getAllProviders().contains(provider) && locations.isProviderEnabled(provider)) {
                // Continuous one-second requests capture short signal recoveries; no polling of old fixes.
                locations.requestLocationUpdates(provider, 1_000, 0, this, Looper.getMainLooper());
            }
        } catch (SecurityException | IllegalArgumentException ignored) { /* Other providers and the local timer remain active. */ }
    }
    @Override public void onLocationChanged(Location location) {
        if (session == null) return;
        if (SessionStore.WAITING_LOCATION.equals(session.optString("phase"))) {
            startPendingJourney(location);
            return;
        }
        if (transferMovement != null) {
            try {
                if (transferMovement.observe(location) && AlarmScheduler.allowed(this)) {
                    JSONObject next = new SessionStore(this).continueJourney(location);
                    AlarmScheduler.arm(this, next);
                    onStartCommand(null, 0, 0);
                } else {
                    long now = SystemClock.elapsedRealtime();
                    if (matchLocation(expectedTransferStops, location, now) == null) tryRouteCorrection(location, now);
                    else routeCorrection.reset();
                }
            } catch (Exception ignored) { /* A stale transfer fix cannot activate a different session. */ }
            return;
        }
        if (!current(session.optString("token"))) return;
        try {
            long now = SystemClock.elapsedRealtime();
            StationMatcher.Fix fix = matchLocation(SessionStore.stations(session), location, now);
            if (fix == null) {
                tryRouteCorrection(location, now);
                return;
            }
            routeCorrection.reset();
            double previousPosition = engine.position();
            if (engine.correctPosition(fix.position, fix.stationIndex, fix.measuredElapsed, now)) {
                if (fix.position < previousPosition - .5) {
                    SessionStore.associate(session, null); resetAssociation.set(true);
                }
                deviceFix.set(new AutoTrainMatcher.DeviceFix(location.getLatitude(), location.getLongitude(), location.getAccuracy(),
                        fix.position, fix.stationIndex, fix.measuredElapsed, System.currentTimeMillis() - (now - fix.measuredElapsed)));
                lastObservation = fix.measuredElapsed; source = "Ubicación del móvil · corrigiendo el recorrido";
                session.put("positionSource", "GPS").put("gpsAccuracy", Math.round(location.getAccuracy())).put("gpsProvider", location.getProvider())
                        .put("gpsState", "Señal precisa recibida");
                publish();
            }
        } catch (Exception ignored) { /* A rejected fix never changes the alarm. */ }
    }

    private void startPendingJourney(Location location) {
        try {
            if (!AlarmScheduler.allowed(this)) return;
            SessionStore store = new SessionStore(this);
            String token = session.optString("token");
            String target = session.getString("destinationId");
            JourneyPlan initial = null;
            if (SessionStore.needsOrigin(session)) {
                if (!location.hasAccuracy() || location.getAccuracy() <= 0 || location.getAccuracy() > 80) return;
                if (routePlanner == null) { pendingFix = new Location(location); return; }
                AutomaticRoutePlanner.Place origin = routePlanner.nearest(location.getLatitude(), location.getLongitude(), 150);
                if (origin == null || origin == routePlanner.place(target)) return;
                initial = routePlanner.plan(origin.id, target, session.getInt("arrivalAdvance"));
            }
            JSONObject started = store.startPendingLocation(token, location, matcher, initial);
            if (started == null && !SessionStore.needsOrigin(session)) {
                if (routePlanner == null) { pendingFix = new Location(location); return; }
                JourneyPlan alternative = routeCorrection.observe(routePlanner, target, session.optInt("arrivalAdvance", 1), location, SystemClock.elapsedRealtime());
                if (alternative == null) return;
                started = store.startPendingLocation(token, location, new StationMatcher(), alternative);
            }
            if (started != null) {
                AlarmScheduler.arm(this, started);
                onStartCommand(null, 0, 0);
            }
        } catch (Exception ignored) { /* Continue waiting for a usable location. */ }
    }

    private StationMatcher.Fix matchLocation(List<Station> stations, Location location, long now) {
        return matcher.match(stations, location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : Float.MAX_VALUE, location.getElapsedRealtimeNanos() / 1_000_000, now);
    }

    private void tryRouteCorrection(Location location, long now) throws Exception {
        String destination = session.optString("destinationId");
        if (routePlanner == null || destination.isEmpty()) return;
        JourneyPlan replacement = routeCorrection.observe(routePlanner, destination, session.optInt("arrivalAdvance", 1), location, now);
        if (replacement != null && AlarmScheduler.allowed(this)) {
            JSONObject revised = new SessionStore(this).replaceRoute(session, replacement, location);
            AlarmScheduler.arm(this, revised);
            onStartCommand(null, 0, 0);
        }
    }
    private void publish() {
        if (session == null || !current(session.optString("token"))) { stopSelf(); return; }
        try {
            long now = SystemClock.elapsedRealtime(), before = SessionStore.wakeAt(session, now);
            long deadline = engine.deadline(now);
            SessionStore.capture(session, engine, now);
            session.put("status", source).put("alarmReason", engine.usesPrediction(now)
                    ? "Aviso anticipado por previsión de Renfe" : "Aviso por tiempo estimado desde la última posición");
            if (!new SessionStore(this).save(session)) { stopSelf(); return; }
            if (engine.shouldAlert(now)) {
                if (SessionStore.silentTransfer(session)) {
                    if (JourneyActions.advanceSilently(this, session.optString("token"))) onStartCommand(null, 0, 0);
                    return;
                }
                String reason = engine.confirmedIndex() >= engine.alertIndex() ? "Aviso en " + SessionStore.stations(session).get(engine.confirmedIndex()).name
                        : now < deadline ? "Renfe: te aproximas a " + engine.alertStation().name : session.optString("alarmReason");
                AlarmService.ring(this, session.getString("token"), reason); stopSelf(); return;
            }
            if (before != SessionStore.wakeAt(session, now) && AlarmScheduler.allowed(this)) AlarmScheduler.arm(this, session);
            String title = (session.optBoolean("transferAcknowledged") ? "Transbordo en " : "Aviso en ") + engine.alertStation().name;
            getSystemService(NotificationManager.class).notify(10, Notifications.build(this, false, title,
                    session.optString("lineName") + " · " + source, session.getString("token")));
        } catch (Exception error) { stopSelf(); }
    }
    @Override public void onProviderEnabled(String provider) { configureLocation(); }
    @Override public void onProviderDisabled(String provider) { configureLocation(); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (detection != null) detection.close();
        if (listeningForProviders) unregisterReceiver(providerChanges);
        resetTracking();
        super.onDestroy();
    }
}
