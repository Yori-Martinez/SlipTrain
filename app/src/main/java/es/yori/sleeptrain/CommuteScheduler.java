package es.yori.sleeptrain;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/** Installs one exact alarm per direction. Each trigger arms the next one before searching for movement. */
public final class CommuteScheduler {
    static final String DETECT = "es.yori.sleeptrain.DETECT_COMMUTE";
    static final String DISMISS = "es.yori.sleeptrain.DISMISS_COMMUTE";
    private static final String PROFILE = "profileId";
    private static final String RETURNING = "returning";
    private static final String SCHEDULED_AT = "scheduledAt";
    public static final Duration DETECTION_WINDOW = Duration.ofMinutes(30);

    private CommuteScheduler() { }

    public static final class Occurrence {
        public final String profileId;
        public final boolean returning;
        public final ZonedDateTime when;
        public final String originId;
        public final String destinationId;

        private Occurrence(CommuteProfile profile, boolean returning, ZonedDateTime when) {
            profileId = profile.id;
            this.returning = returning;
            this.when = when;
            originId = returning ? profile.destinationId : profile.originId;
            destinationId = returning ? profile.originId : profile.destinationId;
        }
    }

    public static Occurrence next(CommuteProfile profile, Instant after, ZoneId zone) {
        return sooner(next(profile, false, after, zone), next(profile, true, after, zone));
    }

    public static Occurrence next(List<CommuteProfile> profiles, Instant after, ZoneId zone) {
        Occurrence result = null;
        for (CommuteProfile profile : profiles) result = sooner(result, next(profile, after, zone));
        return result;
    }

    private static Occurrence sooner(Occurrence a, Occurrence b) {
        if (a == null) return b;
        if (b == null || !b.when.isBefore(a.when)) return a;
        return b;
    }

    static Occurrence next(CommuteProfile profile, boolean returning, Instant after, ZoneId zone) {
        int minute = returning ? profile.returnMinute : profile.outboundMinute;
        if (!profile.enabled || minute < 0) return null;
        LocalDate today = after.atZone(zone).toLocalDate();
        LocalTime hour = LocalTime.of(minute / 60, minute % 60);
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate day = today.plusDays(offset);
            int weekday = 1 << (day.getDayOfWeek().getValue() - 1);
            if ((weekday & profile.daysMask) == 0) continue;
            ZonedDateTime window = day.atTime(hour).atZone(zone);
            if (window.toInstant().isAfter(after)) return new Occurrence(profile, returning, window);
        }
        return null;
    }

    public static boolean hasBackgroundLocation(Context context) {
        return Build.VERSION.SDK_INT < 29 || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean ready(Context context) {
        LocationManager locations = context.getSystemService(LocationManager.class);
        boolean enabled = Build.VERSION.SDK_INT >= 28 ? locations.isLocationEnabled() : locations.isProviderEnabled(LocationManager.GPS_PROVIDER);
        return enabled && permitted(context);
    }

    private static boolean permitted(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && hasBackgroundLocation(context) && AlarmScheduler.allowed(context)
                && context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    public static void refresh(Context context) {
        for (CommuteProfile profile : new CommuteStore(context).list()) refresh(context, profile);
    }

    static void refresh(Context context, CommuteProfile profile) {
        schedule(context, profile, false);
        schedule(context, profile, true);
    }

    static void schedule(Context context, CommuteProfile profile, boolean returning) {
        Instant now = Instant.now();
        long attemptedAt = new CommuteStore(context).attemptedAt(profile.id, returning);
        Occurrence next = permitted(context) ? forScheduling(profile, returning, now, ZoneId.systemDefault(), attemptedAt) : null;
        if (next == null) {
            cancel(context, profile.id, returning);
            return;
        }
        Intent intent = alarmIntent(context, profile.id, returning);
        fill(intent, next);
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    Math.max(now.toEpochMilli() + 1_000, next.when.toInstant().toEpochMilli()), pending);
        } catch (SecurityException ignored) {
            // The user can revoke exact alarm access between the capability check and scheduling.
            pending.cancel();
        }
    }

    static Occurrence forScheduling(CommuteProfile profile, boolean returning, Instant now, ZoneId zone, long attemptedAt) {
        // A pending broadcast can arrive after the app opens or the device restarts. Keep the current window until claimed.
        Occurrence current = next(profile, returning, now.minus(DETECTION_WINDOW), zone);
        if (current == null || current.when.toInstant().toEpochMilli() != attemptedAt) return current;
        return next(profile, returning, now, zone);
    }

    static void cancel(Context context, String id) {
        cancel(context, id, false);
        cancel(context, id, true);
    }

    private static void cancel(Context context, String id, boolean returning) {
        PendingIntent pending = PendingIntent.getBroadcast(context, 0, alarmIntent(context, id, returning),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pending == null) return;
        context.getSystemService(AlarmManager.class).cancel(pending);
        pending.cancel();
    }

    private static Intent alarmIntent(Context context, String id, boolean returning) {
        Uri key = new Uri.Builder().scheme("sleeptrain").authority("routine").appendPath(id)
                .appendPath(returning ? "return" : "outbound").build();
        return new Intent(context, CommuteAlarmReceiver.class).setAction(DETECT).setData(key);
    }

    static Intent detectionIntent(Context context, Occurrence occurrence) {
        return fill(new Intent(context, TripService.class).setAction(DETECT), occurrence);
    }

    private static Intent fill(Intent intent, Occurrence occurrence) {
        return intent.putExtra(PROFILE, occurrence.profileId).putExtra(RETURNING, occurrence.returning)
                .putExtra(SCHEDULED_AT, occurrence.when.toInstant().toEpochMilli());
    }

    public static boolean isDetection(Intent intent) {
        return intent != null && DETECT.equals(intent.getAction());
    }

    static CommuteProfile profile(Context context, Intent intent) {
        return new CommuteStore(context).find(intent.getStringExtra(PROFILE));
    }

    static boolean returning(Intent intent) { return intent.getBooleanExtra(RETURNING, false); }

    static Occurrence activeOccurrence(CommuteProfile profile, Intent intent, Instant now, ZoneId zone) {
        if (profile == null) return null;
        long scheduled = intent.getLongExtra(SCHEDULED_AT, -1);
        if (scheduled <= 0) return null;
        Instant start = Instant.ofEpochMilli(scheduled);
        if (now.isBefore(start) || !now.isBefore(start.plus(DETECTION_WINDOW))) return null;
        Occurrence current = next(profile, returning(intent), start.minusMillis(1), zone);
        return current != null && current.when.toInstant().equals(start) ? current : null;
    }
}
