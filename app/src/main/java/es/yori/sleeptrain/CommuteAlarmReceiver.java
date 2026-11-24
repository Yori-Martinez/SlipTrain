package es.yori.sleeptrain;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.Instant;
import java.time.ZoneId;

/** Delivers user-configured exact alarms and re-arms them on clock or permission changes. */
public final class CommuteAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (CommuteScheduler.DISMISS.equals(intent.getAction())) {
            if (new SessionStore(context).load() == null) context.stopService(new Intent(context, TripService.class));
            return;
        }
        if (!CommuteScheduler.isDetection(intent)) {
            CommuteScheduler.refresh(context);
            return;
        }
        CommuteProfile profile = CommuteScheduler.profile(context, intent);
        if (profile == null) return;
        CommuteScheduler.Occurrence occurrence = CommuteScheduler.activeOccurrence(profile, intent, Instant.now(), ZoneId.systemDefault());
        boolean claimed = occurrence != null && new CommuteStore(context).claim(occurrence);
        CommuteScheduler.schedule(context, profile, CommuteScheduler.returning(intent));
        if (!claimed || !CommuteScheduler.ready(context) || new SessionStore(context).load() != null) return;
        try {
            context.startForegroundService(CommuteScheduler.detectionIntent(context, occurrence));
        } catch (IllegalStateException | SecurityException ignored) {
            // An OS restriction can suppress this window. The next scheduled window remains armed.
        }
    }
}
