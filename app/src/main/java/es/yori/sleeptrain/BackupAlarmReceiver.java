package es.yori.sleeptrain;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import org.json.JSONObject;
import es.yori.sleeptrain.core.JourneyEngine;

public final class BackupAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        JSONObject session = new SessionStore(context).load();
        if (session == null || !session.optString("token").equals(intent.getStringExtra("token"))) return;
        if ("STOP".equals(intent.getAction())) { JourneyActions.stop(context); return; }
        if ("ACKNOWLEDGE".equals(intent.getAction())) { JourneyActions.acknowledge(context); return; }
        if (!SessionStore.RIDING.equals(session.optString("phase", SessionStore.RIDING))) return;
        try {
            if (!session.optBoolean("forceAlarm")) {
                JourneyEngine engine = SessionStore.engine(session);
                SessionStore.capture(session, engine, SystemClock.elapsedRealtime());
                if (!engine.usesPrediction(SystemClock.elapsedRealtime())) session.put("alarmReason", "Aviso por tiempo estimado desde la última posición");
                new SessionStore(context).save(session);
            }
        } catch (Exception ignored) { return; }
        if (!session.optBoolean("forceAlarm") && SystemClock.elapsedRealtime() + 500 < session.optLong("deadlineElapsed")) {
            if (AlarmScheduler.allowed(context)) AlarmScheduler.arm(context, session);
            return;
        }
        if (SessionStore.silentTransfer(session)) {
            if (JourneyActions.advanceSilently(context, session.optString("token"))) {
                try { context.startForegroundService(new Intent(context, TripService.class)); }
                catch (RuntimeException unavailable) { /* Reopening the journey resumes transfer detection. */ }
            }
        } else AlarmService.ring(context, session.optString("token"), session.optString("alarmReason"));
    }
}

