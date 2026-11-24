package es.yori.sleeptrain;

import android.app.Service;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONObject;

public final class AlarmService extends Service {
    private AlarmPlayer player;
    private PowerManager.WakeLock wakeLock;

    static void ring(Context context, String token, String reason) {
        SessionStore store = new SessionStore(context);
        JSONObject session = store.load();
        if (session == null || !session.optString("token").equals(token) || session.optBoolean("ringing")
                || SessionStore.silentTransfer(session)
                || !SessionStore.RIDING.equals(session.optString("phase", SessionStore.RIDING))) return;
        try {
            session.put("alarmReason", reason).put("forceAlarm", true);
            if (!store.save(session)) return;
            context.startForegroundService(new Intent(context, AlarmService.class).putExtra("token", token));
        } catch (RuntimeException error) {
            try {
                // An exact user alarm can launch playback when a background FGS launch is restricted.
                session.put("deadlineElapsed", SystemClock.elapsedRealtime() + 1_000);
                store.save(session);
                if (AlarmScheduler.allowed(context)) AlarmScheduler.arm(context, session);
            } catch (Exception ignored) { /* Permission revocation is surfaced when the app reopens. */ }
        } catch (Exception ignored) { /* Session may already have been canceled. */ }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SessionStore store = new SessionStore(this);
        JSONObject session = store.load();
        if (session == null || !SessionStore.RIDING.equals(session.optString("phase", SessionStore.RIDING))
                || SessionStore.silentTransfer(session) || (!session.optBoolean("forceAlarm") && !session.optBoolean("ringing"))
                || (intent != null && !session.optString("token").equals(intent.getStringExtra("token")))) {
            stopSelf(); return START_NOT_STICKY;
        }
        try {
            boolean transfer = SessionStore.hasTransfer(session);
            String title = transfer ? "Transbordo · " + SessionStore.interchange(session).name
                    : "Despierta · " + SessionStore.engine(session).alertStation().name;
            String token = session.optString("token");
            if (Build.VERSION.SDK_INT >= 29) startForeground(20,
                    Notifications.build(this, true, title, session.optString("alarmReason"), session.optString("token")),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(20, Notifications.build(this, true, title, session.optString("alarmReason"), session.optString("token")));
            session.put("ringing", true);
            if (!store.save(session)) { stopSelf(); return START_NOT_STICKY; }
            AlarmScheduler.cancel(this); stopService(new Intent(this, TripService.class));
            if (player == null) {
                wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SlipTrain:alarm");
                wakeLock.acquire(30 * 60_000L);
                player = new AlarmPlayer(this, status -> {
                    try {
                        JSONObject active = store.load();
                        if (active == null || !token.equals(active.optString("token"))) return;
                        active.put("status", status); store.save(active);
                        getSystemService(NotificationManager.class).notify(20,
                                Notifications.build(this, true, title, status, active.optString("token")));
                    } catch (Exception ignored) { /* Audio continues if a status update fails. */ }
                });
                player.start(session.optString("audioMode", "auto"), session.optBoolean("vibration", true),
                        session.optBoolean("speakerFallback", true), session.optString(AlarmAudio.SESSION_KEY));
            }
            return START_STICKY;
        } catch (Exception error) { stopSelf(); return START_NOT_STICKY; }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (player != null) player.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
