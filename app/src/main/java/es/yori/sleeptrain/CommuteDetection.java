package es.yori.sleeptrain;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import es.yori.sleeptrain.data.TransitRepository;
import es.yori.sleeptrain.domain.AutomaticRoutePlanner;
import es.yori.sleeptrain.domain.JourneyPlan;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/** The scheduled location phase runs inside TripService and hands off without restarting the foreground service. */
final class CommuteDetection implements LocationListener, AutoCloseable {
    private static final String CHANNEL = "commute_detection";
    private static final int NOTIFICATION_ID = 12;

    private static final class Window {
        final Intent intent;
        final CommuteScheduler.Occurrence occurrence;
        final long openedAt;
        final long expiresAt;
        final String routeKey;
        List<Departure> departures = Collections.emptyList();

        Window(Intent intent, CommuteScheduler.Occurrence occurrence, String routeKey) {
            this.intent = new Intent(intent);
            this.occurrence = occurrence;
            this.routeKey = routeKey;
            openedAt = SystemClock.elapsedRealtime();
            long remaining = occurrence.when.toInstant().plus(CommuteScheduler.DETECTION_WINDOW).toEpochMilli() - System.currentTimeMillis();
            expiresAt = openedAt + Math.max(0, Math.min(remaining, CommuteScheduler.DETECTION_WINDOW.toMillis()));
        }
    }

    private static final class Departure {
        final JourneyPlan plan;
        final MovementEvidence movement;

        Departure(JourneyPlan plan) {
            this.plan = plan;
            movement = new MovementEvidence(plan.legs.get(0).stations);
        }
    }

    private final Service owner;
    private final IntConsumer onDeparture;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LocationManager locations;
    private final Map<String, Window> windows = new LinkedHashMap<>();
    private ExecutorService worker;
    private int latestStartId;
    private boolean listening;
    private boolean closed;
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (closed) return;
            windows.values().removeIf(window -> currentProfile(window) == null);
            if (windows.isEmpty() || !CommuteScheduler.ready(owner)) finishSearch();
            else {
                listen();
                handler.postDelayed(this, 5_000);
            }
        }
    };

    CommuteDetection(Service owner, IntConsumer onDeparture) {
        this.owner = owner;
        this.onDeparture = onDeparture;
        locations = owner.getSystemService(LocationManager.class);
    }

    int start(Intent intent, int startId) {
        latestStartId = startId;
        CommuteProfile profile = CommuteScheduler.profile(owner, intent);
        CommuteScheduler.Occurrence occurrence = CommuteScheduler.activeOccurrence(profile, intent, Instant.now(), ZoneId.systemDefault());
        if (occurrence == null || !CommuteScheduler.ready(owner)) {
            if (!windows.isEmpty()) return Service.START_STICKY;
            finishSearch();
            return Service.START_NOT_STICKY;
        }
        String key = occurrence.profileId + ":" + occurrence.returning + ":" + occurrence.when.toInstant();
        if (windows.containsKey(key)) return Service.START_STICKY;
        if (windows.isEmpty()) {
            try {
                Notification notification = notification();
                if (Build.VERSION.SDK_INT >= 29) owner.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                else owner.startForeground(NOTIFICATION_ID, notification);
            } catch (SecurityException | IllegalStateException unavailable) {
                finishSearch();
                return Service.START_NOT_STICKY;
            }
        }
        Window window = new Window(intent, occurrence, profile.route(occurrence.returning));
        windows.put(key, window);
        if (worker == null) worker = Executors.newSingleThreadExecutor();
        worker.execute(() -> loadRoute(key, window));
        listen();
        handler.removeCallbacks(check);
        handler.postDelayed(check, 5_000);
        return Service.START_STICKY;
    }

    private void loadRoute(String key, Window window) {
        try {
            AutomaticRoutePlanner planner = new AutomaticRoutePlanner(new TransitRepository(owner).load());
            List<Departure> departures = new ArrayList<>();
            String from = window.occurrence.originId, to = window.occurrence.destinationId;
            JourneyPlan.Leg first = null;
            if (!window.routeKey.isEmpty()) {
                JourneyPlan preferred = planner.restore(window.routeKey, from, to, 1);
                departures.add(new Departure(preferred));
                first = preferred.legs.get(0);
            }
            for (JourneyPlan plan : planner.departurePlans(from, to, 1)) {
                JourneyPlan.Leg alternate = plan.legs.get(0);
                if (first == null || !first.line.id.equals(alternate.line.id) || first.forward != alternate.forward) {
                    departures.add(new Departure(plan));
                }
            }
            if (departures.isEmpty()) throw new IllegalArgumentException("No hay ruta para esta salida");
            handler.post(() -> {
                if (closed || windows.get(key) != window) return;
                window.departures = departures;
            });
        } catch (Exception unavailable) {
            handler.post(() -> {
                if (closed) return;
                windows.remove(key, window);
                if (windows.isEmpty()) finishSearch();
            });
        }
    }

    private void listen() {
        if (listening || locations == null) return;
        listening = requestProvider(LocationManager.GPS_PROVIDER);
        if (Build.VERSION.SDK_INT >= 31) listening |= requestProvider(LocationManager.FUSED_PROVIDER);
    }

    private boolean requestProvider(String provider) {
        try {
            if (!locations.getAllProviders().contains(provider)) return false;
            locations.requestLocationUpdates(provider, 1_000, 0, this, Looper.getMainLooper());
            return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Retry while the short window remains open in case the provider becomes available.
            return false;
        }
    }

    private CommuteProfile currentProfile(Window window) {
        if (SystemClock.elapsedRealtime() >= window.expiresAt) return null;
        CommuteProfile current = CommuteScheduler.profile(owner, window.intent);
        CommuteScheduler.Occurrence occurrence = CommuteScheduler.activeOccurrence(current, window.intent, Instant.now(), ZoneId.systemDefault());
        if (occurrence == null || !window.occurrence.originId.equals(occurrence.originId)
                || !window.occurrence.destinationId.equals(occurrence.destinationId)
                || !window.routeKey.equals(current.route(occurrence.returning))) return null;
        return current;
    }

    @Override public void onLocationChanged(Location location) {
        if (closed || !CommuteScheduler.ready(owner)) return;
        SessionStore sessions = new SessionStore(owner);
        if (sessions.load() != null) {
            close();
            onDeparture.accept(latestStartId);
            return;
        }
        Window match = null;
        JourneyPlan plan = null;
        CommuteProfile profile = null;
        for (Window window : windows.values()) {
            if (location.getElapsedRealtimeNanos() / 1_000_000L < window.openedAt) continue;
            CommuteProfile current = currentProfile(window);
            if (current == null) continue;
            JourneyPlan observed = detectDeparture(window, location);
            if (observed != null && (match == null || window.occurrence.when.isAfter(match.occurrence.when))) {
                match = window;
                plan = observed;
                profile = current;
            }
        }
        if (match == null) return;
        try {
            AlarmScheduler.arm(owner, sessions.createScheduled(plan, profile, match.occurrence, location));
        } catch (Exception failure) {
            sessions.clear();
            finishSearch();
            return;
        }
        close();
        onDeparture.accept(latestStartId);
    }

    private JourneyPlan detectDeparture(Window window, Location location) {
        JourneyPlan alternate = null;
        for (int i = 0; i < window.departures.size(); i++) {
            Departure departure = window.departures.get(i);
            if (!departure.movement.observe(location)) continue;
            if (window.routeKey.isEmpty() || i == 0) return departure.plan;
            if (alternate != null) return null;
            alternate = departure.plan;
        }
        return alternate;
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private Notification notification() {
        NotificationManager notifications = owner.getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Viajes programados", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(owner, 12, new Intent(owner, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent dismiss = PendingIntent.getBroadcast(owner, 12, new Intent(owner, CommuteAlarmReceiver.class)
                .setAction(CommuteScheduler.DISMISS), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(owner, CHANNEL).setSmallIcon(R.drawable.ic_train)
                .setContentTitle("Buscando el inicio de tu viaje")
                .setContentText("El seguimiento empezará cuando detectemos movimiento en tu ruta.")
                .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Omitir este viaje", dismiss).build()).build();
    }

    private void finishSearch() {
        close();
        owner.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        owner.stopSelfResult(latestStartId);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        if (listening && locations != null) {
            try { locations.removeUpdates(this); } catch (SecurityException ignored) { }
        }
        if (worker != null) worker.shutdownNow();
        windows.clear();
    }
}
