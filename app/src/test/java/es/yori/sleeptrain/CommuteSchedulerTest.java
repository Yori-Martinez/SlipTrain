package es.yori.sleeptrain;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class CommuteSchedulerTest {
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private Application app;
    private CommuteStore store;
    private ShadowAlarmManager alarms;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        store = new CommuteStore(app);
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION, Manifest.permission.POST_NOTIFICATIONS);
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        alarms = shadowOf(app.getSystemService(AlarmManager.class));
    }

    @Test public void outboundAndReturnSwapTheEndpoints() {
        CommuteProfile profile = routine(CommuteProfile.DAYS_WEEKDAYS, 14 * 60);
        CommuteScheduler.Occurrence outbound = CommuteScheduler.next(profile, at("2026-11-20T06:55"), MADRID);
        assertFalse(outbound.returning);
        assertEquals("mostoles", outbound.originId);
        assertEquals("alfonso-xii", outbound.destinationId);
        assertEquals(at("2026-11-20T07:00"), outbound.when.toInstant());
        CommuteScheduler.Occurrence returning = CommuteScheduler.next(profile, at("2026-11-20T08:00"), MADRID);
        assertTrue(returning.returning);
        assertEquals("alfonso-xii", returning.originId);
        assertEquals("mostoles", returning.destinationId);
        assertEquals(at("2026-11-20T14:00"), returning.when.toInstant());
    }

    @Test public void weekdayPresetsSkipUnselectedDays() {
        assertEquals(at("2026-11-23T07:00"), CommuteScheduler.next(routine(CommuteProfile.DAYS_WEEKDAYS, -1), at("2026-11-20T20:00"), MADRID).when.toInstant());
        assertEquals(at("2026-11-20T07:00"), CommuteScheduler.next(routine(CommuteProfile.DAYS_MWF, -1), at("2026-11-19T06:00"), MADRID).when.toInstant());
        assertEquals(at("2026-11-21T07:00"), CommuteScheduler.next(routine(CommuteProfile.DAYS_WEEKEND, -1), at("2026-11-20T06:00"), MADRID).when.toInstant());
    }

    @Test public void civilTimeRemainsStableAcrossDaylightSavingChange() {
        CommuteProfile profile = routine(CommuteProfile.DAYS_WEEKDAYS, -1);
        assertEquals(Instant.parse("2026-03-30T05:00:00Z"),
                CommuteScheduler.next(profile, at("2026-03-27T08:00"), MADRID).when.toInstant());
    }

    @Test public void disabledAndOneWayProfilesScheduleOnlyAuthorizedDirections() {
        CommuteProfile roundTrip = routine(CommuteProfile.DAYS_WEEKDAYS, 14 * 60);
        CommuteProfile oneWay = routine(CommuteProfile.DAYS_WEEKEND, -1);
        store.save(roundTrip);
        store.save(oneWay);
        assertEquals(3, alarms.getScheduledAlarms().size());
        store.save(roundTrip.withEnabled(false));
        assertEquals(1, alarms.getScheduledAlarms().size());
        assertNull(CommuteScheduler.next(roundTrip.withEnabled(false), at("2026-11-20T06:00"), MADRID));
        assertEquals(oneWay.id, CommuteScheduler.next(List.of(roundTrip.withEnabled(false), oneWay), at("2026-11-20T06:00"), MADRID).profileId);
        store.remove(oneWay.id);
        assertTrue(alarms.getScheduledAlarms().isEmpty());
    }

    @Test public void permissionChangesRescheduleStoredEnabledProfiles() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        CommuteProfile profile = routine(CommuteProfile.DAYS_WEEKDAYS, 14 * 60);
        store.save(profile);
        assertTrue(alarms.getScheduledAlarms().isEmpty());
        assertEquals("audio-id", store.find(profile.id).audioId);
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        CommuteScheduler.refresh(app);
        assertEquals(2, alarms.getScheduledAlarms().size());
    }

    @Test public void onlyCurrentOccurrenceInsideItsDetectionWindowIsEligible() {
        CommuteProfile profile = routine(CommuteProfile.DAYS_WEEKDAYS, -1);
        CommuteScheduler.Occurrence occurrence = CommuteScheduler.next(profile, at("2026-11-20T06:59"), MADRID);
        Intent detection = CommuteScheduler.detectionIntent(app, occurrence);
        assertNotNull(CommuteScheduler.activeOccurrence(profile, detection, at("2026-11-20T07:12"), MADRID));
        assertNull(CommuteScheduler.activeOccurrence(profile, detection, at("2026-11-20T06:59"), MADRID));
        assertNull(CommuteScheduler.activeOccurrence(profile, detection, at("2026-11-20T07:30"), MADRID));
        assertNull(CommuteScheduler.activeOccurrence(profile.withEnabled(false), detection, at("2026-11-20T07:12"), MADRID));
    }

    @Test public void refreshPreservesPendingWindowAndClaimsItAtMostOnce() {
        CommuteProfile profile = routine(CommuteProfile.DAYS_WEEKDAYS, 14 * 60);
        Instant now = at("2026-11-20T07:01");
        CommuteScheduler.Occurrence pending = CommuteScheduler.forScheduling(profile, false, now, MADRID, -1);
        assertEquals(at("2026-11-20T07:00"), pending.when.toInstant());
        assertTrue(store.claim(pending));
        CommuteStore restored = new CommuteStore(app);
        assertFalse(restored.claim(pending));
        long attempted = restored.attemptedAt(profile.id, false);
        assertEquals(at("2026-11-23T07:00"), CommuteScheduler.forScheduling(profile, false, now, MADRID, attempted).when.toInstant());
        assertEquals(at("2026-11-20T14:00"), CommuteScheduler.forScheduling(profile, true, now, MADRID, -1).when.toInstant());
    }

    private static CommuteProfile routine(int days, int back) {
        return new CommuteProfile("", "mostoles", "alfonso-xii", 7 * 60, back, days, "", "audio-id", true, true);
    }

    private static Instant at(String local) { return LocalDateTime.parse(local).atZone(MADRID).toInstant(); }
}
