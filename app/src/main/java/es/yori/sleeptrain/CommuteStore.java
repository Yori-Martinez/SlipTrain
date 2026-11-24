package es.yori.sleeptrain;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Each routine is an independent preference, so it can be updated or removed independently. */
public final class CommuteStore {
    private final Context context;
    private final SharedPreferences preferences;
    private final SharedPreferences attempts;

    public CommuteStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("commute_routines", Context.MODE_PRIVATE);
        attempts = this.context.getSharedPreferences("commute_attempts", Context.MODE_PRIVATE);
    }

    public List<CommuteProfile> list() {
        List<CommuteProfile> result = new ArrayList<>();
        for (Object value : preferences.getAll().values()) {
            CommuteProfile profile = parse(value);
            if (profile != null) result.add(profile);
        }
        result.sort(Comparator.comparingInt((CommuteProfile profile) -> profile.outboundMinute).thenComparing(profile -> profile.id));
        return Collections.unmodifiableList(result);
    }

    public CommuteProfile find(String id) {
        return id == null ? null : parse(preferences.getAll().get(id));
    }

    public void save(CommuteProfile profile) {
        try {
            // Persist before installing the alarm, including when the app process is later killed.
            if (!preferences.edit().putString(profile.id, profile.toJson().toString()).commit()) throw new IllegalStateException("No se pudo guardar la alarma");
        } catch (JSONException error) {
            throw new IllegalArgumentException("No se pudo guardar la alarma", error);
        }
        CommuteScheduler.refresh(context, profile);
    }

    public void remove(String id) {
        if (!preferences.edit().remove(id).commit()) throw new IllegalStateException("No se pudo borrar la alarma");
        CommuteScheduler.cancel(context, id);
        attempts.edit().remove(attemptKey(id, false)).remove(attemptKey(id, true)).apply();
    }

    long attemptedAt(String id, boolean returning) {
        return attempts.getLong(attemptKey(id, returning), -1);
    }

    /** The receiver and editor use a single app process, and claiming on its main thread is synchronous. */
    boolean claim(CommuteScheduler.Occurrence occurrence) {
        long time = occurrence.when.toInstant().toEpochMilli();
        if (attemptedAt(occurrence.profileId, occurrence.returning) == time) return false;
        return attempts.edit().putLong(attemptKey(occurrence.profileId, occurrence.returning), time).commit();
    }

    private static String attemptKey(String id, boolean returning) { return id + (returning ? ":return" : ":outbound"); }

    private CommuteProfile parse(Object value) {
        if (!(value instanceof String)) return null;
        try {
            return CommuteProfile.fromJson(new JSONObject((String) value));
        } catch (JSONException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
