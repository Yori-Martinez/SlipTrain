package es.yori.sleeptrain;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

final class JourneyActions {
    static void acknowledge(Context context) {
        try {
            SessionStore store = new SessionStore(context);
            JSONObject session = store.load();
            if (session == null || !session.optBoolean("ringing")) return;
            JSONObject next = store.acknowledgeTransfer(session.optString("token"));
            if (next == null) { stop(context); return; }
            AlarmScheduler.cancel(context);
            context.stopService(new Intent(context, TripService.class));
            context.stopService(new Intent(context, AlarmService.class));
            if (!session.optString("destinationId").isEmpty()) {
                if (SessionStore.RIDING.equals(next.optString("phase")) && AlarmScheduler.allowed(context)) AlarmScheduler.arm(context, next);
                try { context.startForegroundService(new Intent(context, TripService.class)); }
                catch (RuntimeException unavailable) { /* The user can reopen the trip to resume transfer detection. */ }
            }
        } catch (Exception error) { stop(context); }
    }

    static boolean advanceSilently(Context context, String token) {
        SessionStore store = new SessionStore(context);
        JSONObject session = store.load();
        if (session == null || !token.equals(session.optString("token")) || !SessionStore.silentTransfer(session)) return false;
        try {
            if (!store.awaitNext(token)) return false;
            AlarmScheduler.cancel(context);
            return true;
        } catch (Exception failure) { return false; }
    }
    static void stop(Context context) {
        new SessionStore(context).clear();
        AlarmScheduler.cancel(context);
        context.stopService(new Intent(context, TripService.class));
        context.stopService(new Intent(context, AlarmService.class));
    }
}

