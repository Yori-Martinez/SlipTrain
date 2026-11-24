package es.yori.sleeptrain;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import org.json.JSONObject;

final class AlarmScheduler {
    static boolean allowed(Context context) {
        return Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }
    private static PendingIntent pending(Context context, String token) {
        return PendingIntent.getBroadcast(context, 50, new Intent(context, BackupAlarmReceiver.class)
                .setAction("ALARM").putExtra("token", token), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static void arm(Context context, JSONObject session) {
        if (!SessionStore.RIDING.equals(session.optString("phase", SessionStore.RIDING))) return;
        long now = SystemClock.elapsedRealtime();
        long deadline = Math.max(now + 1_000, SessionStore.wakeAt(session, now));
        context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending(context, session.optString("token")));
    }
    static void cancel(Context context) {
        PendingIntent intent = pending(context, "");
        context.getSystemService(AlarmManager.class).cancel(intent); intent.cancel();
    }
}

