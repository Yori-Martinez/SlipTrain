package es.yori.sleeptrain;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;
import java.util.UUID;

/** A local, recurring journey in one or both directions. Weekday bits start at Monday. */
public final class CommuteProfile {
    public static final int DAYS_WEEKDAYS = 31;
    public static final int DAYS_MWF = 21;
    public static final int DAYS_WEEKEND = 96;
    public static final int DAYS_EVERY_DAY = 127;

    public final String id;
    public final String originId;
    public final String destinationId;
    public final int outboundMinute;
    public final int returnMinute;
    public final int daysMask;
    public final String label;
    public final String audioId;
    public final boolean vibration;
    public final boolean enabled;
    public final boolean transferAlarm;
    public final String outboundRoute, returnRoute;

    public CommuteProfile(String id, String originId, String destinationId, int outboundMinute, int returnMinute,
                          int daysMask, String label, String audioId, boolean vibration, boolean enabled) {
        this(id, originId, destinationId, outboundMinute, returnMinute, daysMask, label, audioId, vibration, enabled,
                true, "", "");
    }

    public CommuteProfile(String id, String originId, String destinationId, int outboundMinute, int returnMinute,
                          int daysMask, String label, String audioId, boolean vibration, boolean enabled,
                          boolean transferAlarm, String outboundRoute, String returnRoute) {
        this.originId = Objects.requireNonNull(originId);
        this.destinationId = Objects.requireNonNull(destinationId);
        if (originId.isEmpty() || destinationId.isEmpty() || originId.equals(destinationId)) {
            throw new IllegalArgumentException("Elige un origen y un destino distintos");
        }
        if (outboundMinute < 0 || outboundMinute >= 1440 || returnMinute < -1 || returnMinute >= 1440) {
            throw new IllegalArgumentException("Revisa las horas del viaje");
        }
        if (outboundMinute == returnMinute) throw new IllegalArgumentException("La ida y la vuelta necesitan horas distintas");
        if (daysMask == 0 || (daysMask & ~DAYS_EVERY_DAY) != 0) throw new IllegalArgumentException("Elige al menos un día");
        this.id = id == null || id.isEmpty() ? UUID.randomUUID().toString() : id;
        this.outboundMinute = outboundMinute;
        this.returnMinute = returnMinute;
        this.daysMask = daysMask;
        this.label = label == null ? "" : label.trim();
        this.audioId = audioId == null ? "" : audioId;
        this.vibration = vibration;
        this.enabled = enabled;
        this.transferAlarm = transferAlarm;
        this.outboundRoute = outboundRoute == null ? "" : outboundRoute;
        this.returnRoute = returnMinute < 0 || returnRoute == null ? "" : returnRoute;
    }

    public CommuteProfile withEnabled(boolean enabled) {
        return new CommuteProfile(id, originId, destinationId, outboundMinute, returnMinute, daysMask, label, audioId,
                vibration, enabled, transferAlarm, outboundRoute, returnRoute);
    }

    String route(boolean returning) { return returning ? returnRoute : outboundRoute; }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("origin", originId).put("destination", destinationId)
                .put("outboundMinute", outboundMinute).put("returnMinute", returnMinute).put("days", daysMask)
                .put("label", label).put("audioId", audioId).put("vibration", vibration).put("enabled", enabled)
                .put("transferAlarm", transferAlarm).put("outboundRoute", outboundRoute).put("returnRoute", returnRoute);
    }

    static CommuteProfile fromJson(JSONObject value) throws JSONException {
        return new CommuteProfile(value.getString("id"), value.getString("origin"), value.getString("destination"),
                value.getInt("outboundMinute"), value.optInt("returnMinute", -1), value.getInt("days"),
                value.optString("label"), value.optString("audioId"), value.optBoolean("vibration", true), value.optBoolean("enabled"),
                value.optBoolean("transferAlarm", true), value.optString("outboundRoute"), value.optString("returnRoute"));
    }
}
