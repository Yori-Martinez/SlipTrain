package es.yori.sleeptrain;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import es.yori.sleeptrain.core.JourneyEngine;
import es.yori.sleeptrain.core.Station;
import es.yori.sleeptrain.data.TransitRepository;
import es.yori.sleeptrain.data.realtime.RenfeRealtimeRepository;
import es.yori.sleeptrain.data.realtime.RouteEstimates;
import es.yori.sleeptrain.data.realtime.RouteEstimates.Option;
import es.yori.sleeptrain.data.realtime.RouteEstimates.Operator;
import es.yori.sleeptrain.data.realtime.RouteEstimates.Order;
import es.yori.sleeptrain.domain.AutomaticRoutePlanner;
import es.yori.sleeptrain.domain.AutomaticRoutePlanner.Place;
import es.yori.sleeptrain.domain.JourneyPlan;
import es.yori.sleeptrain.domain.TransitLine;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int BG = 0xff101715;
    private static final int CARD = 0xff1c2622;
    private static final int SURFACE = 0xff293730;
    private static final int TEXT = 0xffeff9f2;
    private static final int MUTED = 0xffa7bdb0;
    private static final int MINT = 0xffa3edc5;
    private static final int EDGE = 0xff34463d;
    private static final int GREEN = 0xff193b2c;
    private static final int GOLD = 0xffe9c97b;
    private static final int NOTIFICATIONS = 101, PRECISE = 102, BACKGROUND = 103, AUDIO = 104, AUTO_PRECISE = 105;
    private static final String TRANSFER_ALARM = "transferAlarm";
    private static final Locale SPANISH = new Locale("es", "ES");
    private static final DateTimeFormatter OCCURRENCE = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", SPANISH);
    private static final String[] SHORT_DAYS = {"Lu", "Ma", "Mi", "Ju", "Vi", "Sá", "Do"};
    private static final String[] DAYS = {"Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService liveRoutes = Executors.newSingleThreadExecutor();
    private final RenfeRealtimeRepository planningFeed = new RenfeRealtimeRepository();
    private SessionStore sessions;
    private CommuteStore commutes;
    private SharedPreferences preferences;
    private LocationManager locationManager;
    private AutomaticRoutePlanner planner;
    private LocationListener locationListener;
    private String destinationId = "";
    private Draft draft;
    private StationPicker stationPicker;
    private RouteSelection routeSelection;
    private PreferenceScreen preferenceScreen;
    private String renderedScreen;
    private EditText labelEditor;
    private LinearLayout activeCard, plannedCard, scheduleCard;
    private TextView nowStatus, nowProgress;
    private TextView planningDetail;
    private RouteProgressView routeProgress;
    private TextView scheduleStatus, scheduleDetail;
    private Button arrivalAction, transferAction, planAction, chooseRouteAction, cancelSearchAction, endAction, incidentsAction;
    private boolean awaitingManualLocation;
    private boolean waitingForLocationSettings;
    private String manualOrigin = "", manualRoute = "";
    private boolean waitingForExactAlarm;
    private boolean manualStartRequested;
    private boolean audioForDraft;
    private int secretTaps;
    private long lastSecretTap;
    private AlarmPlayer preview;
    private String previewStatus = "";
    private TextView previewLabel, previewDetail;
    private ImageView previewIcon;
    private Runnable unregisterEditorBack;
    private final Runnable stopPreviewTask = this::stopPreview;

    private final Runnable refreshLive = new Runnable() {
        @Override public void run() {
            if (draft == null && activeCard != null) updateLive();
            main.postDelayed(this, 1_000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        sessions = new SessionStore(this);
        commutes = new CommuteStore(this);
        preferences = getSharedPreferences("preferences", MODE_PRIVATE);
        locationManager = getSystemService(LocationManager.class);
        destinationId = preferences.getString("lastDestination", "");
        if (state != null) {
            audioForDraft = state.getBoolean("audioForDraft");
            String savedPreference = state.getString("preferenceScreen");
            if (savedPreference != null) preferenceScreen = PreferenceScreen.valueOf(savedPreference);
            waitingForExactAlarm = state.getBoolean("waitingForExactAlarm");
            manualStartRequested = state.getBoolean("manualStartRequested");
            waitingForLocationSettings = state.getBoolean("waitingForLocationSettings");
            awaitingManualLocation = state.getBoolean("awaitingManualLocation");
            manualOrigin = state.getString("manualOrigin", "");
            manualRoute = state.getString("manualRoute", "");
            Bundle saved = state.getBundle("draft");
            if (saved != null) draft = new Draft(saved);
            Bundle savedPicker = state.getBundle("stationPicker");
            if (savedPicker != null) stationPicker = new StationPicker(savedPicker);
            Bundle savedRoutes = state.getBundle("routeSelection");
            if (savedRoutes != null) routeSelection = new RouteSelection(savedRoutes);
        }
        render();
        worker.execute(() -> {
            try {
                AutomaticRoutePlanner loaded = new AutomaticRoutePlanner(new TransitRepository(this).load());
                main.post(() -> {
                    if (isDestroyed()) return;
                    planner = loaded;
                    render();
                    if (routeSelection != null) loadRoutes(routeSelection);
                    else if (awaitingManualLocation) listenForOrigin();
                    else if (waitingForExactAlarm && AlarmScheduler.allowed(this)) {
                        waitingForExactAlarm = false;
                        activateManual();
                    }
                });
            } catch (Exception error) {
                main.post(() -> { if (!isDestroyed()) notifyUser("No se pudo cargar la red de transporte."); });
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        CommuteScheduler.refresh(this);
        JSONObject session = sessions.load();
        if (session != null && !session.optBoolean("ringing") && !session.optBoolean("forceAlarm")
                && (SessionStore.RIDING.equals(session.optString("phase", SessionStore.RIDING))
                    || !session.optString("destinationId").isEmpty())) {
            try { startForegroundService(new Intent(this, TripService.class)); } catch (RuntimeException ignored) { }
        }
        if (waitingForExactAlarm && planner != null && AlarmScheduler.allowed(this)) {
            waitingForExactAlarm = false;
            main.post(this::activateManual);
        }
        if (waitingForLocationSettings && locationEnabled()) {
            waitingForLocationSettings = false;
            awaitingManualLocation = true;
        }
        if (awaitingManualLocation) listenForOrigin();
        if (!hasSubpage()) render();
        main.post(refreshLive);
    }

    @Override protected void onPause() {
        main.removeCallbacks(refreshLive);
        detachOriginListener();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (unregisterEditorBack != null) unregisterEditorBack.run();
        stopPreview();
        cancelLocationSearch();
        worker.shutdownNow();
        liveRoutes.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        syncDraft();
        if (draft != null) out.putBundle("draft", draft.bundle());
        if (stationPicker != null) out.putBundle("stationPicker", stationPicker.bundle());
        if (routeSelection != null) out.putBundle("routeSelection", routeSelection.bundle());
        if (preferenceScreen != null) out.putString("preferenceScreen", preferenceScreen.name());
        out.putBoolean("audioForDraft", audioForDraft);
        out.putBoolean("waitingForExactAlarm", waitingForExactAlarm);
        out.putBoolean("manualStartRequested", manualStartRequested);
        out.putBoolean("waitingForLocationSettings", waitingForLocationSettings);
        out.putBoolean("awaitingManualLocation", awaitingManualLocation);
        out.putString("manualOrigin", manualOrigin);
        out.putString("manualRoute", manualRoute);
        super.onSaveInstanceState(out);
    }

    @SuppressLint("GestureBackNavigation") // API 26–32. Newer devices use the platform callback below.
    @Override public void onBackPressed() {
        if (hasSubpage()) closeSubpage();
        else super.onBackPressed();
    }

    private boolean hasSubpage() {
        return draft != null || stationPicker != null || routeSelection != null || preferenceScreen != null;
    }

    private void closeSubpage() {
        if (preferenceScreen != null) {
            stopPreview();
            secretTaps = 0;
            preferenceScreen = switch (preferenceScreen) {
                case SETTINGS -> null;
                case ALARM_AUDIO -> audioForDraft ? null : PreferenceScreen.SOUND;
                default -> PreferenceScreen.SETTINGS;
            };
        } else if (stationPicker != null) {
            hideKeyboard();
            stationPicker = null;
        } else if (routeSelection != null) {
            routeSelection = null;
        } else { closeEditor(); return; }
        render();
    }

    private void closeEditor() {
        stopPreview();
        cancelLocationSearch();
        draft = null;
        render();
    }

    private void updateBackNavigation() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (hasSubpage() && unregisterEditorBack == null) {
            OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
            OnBackInvokedCallback callback = this::closeSubpage;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            unregisterEditorBack = () -> dispatcher.unregisterOnBackInvokedCallback(callback);
        } else if (!hasSubpage() && unregisterEditorBack != null) {
            unregisterEditorBack.run();
            unregisterEditorBack = null;
        }
    }

    private void render() {
        String screen = preferenceScreen != null ? preferenceScreen.name() : stationPicker != null
                ? (stationPicker.choosingOrigin ? "origin" : "destination") : routeSelection != null
                ? (routeSelection.returning ? "returnRoutes" : "routes") : draft != null ? "editor" : "home";
        boolean animate = renderedScreen != null && !renderedScreen.equals(screen);
        renderedScreen = screen;
        syncDraft();
        labelEditor = null;
        previewLabel = previewDetail = null;
        previewIcon = null;
        activeCard = null;
        if (stationPicker != null || routeSelection != null) {
            LinearLayout page = column();
            setPage(page);
            if (stationPicker != null) stationsPage(page, stationPicker);
            else routesPage(page, routeSelection);
            if (animate) ViewMotion.enter(page, 5);
            updateBackNavigation();
            return;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        setPage(scroll);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(page);
        if (preferenceScreen != null) preferencesPage(page);
        else if (draft == null) home(page); else editor(page);
        if (animate) ViewMotion.enter(page, 5);
        updateBackNavigation();
    }

    private void setPage(View page) {
        page.setBackgroundColor(BG);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                page.setPadding(edges.left, edges.top, edges.right, edges.bottom);
            } else {
                page.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(page);
    }

    private void home(LinearLayout page) {
        LinearLayout bar = row(page);
        TextView name = title(bar, getString(R.string.app_name), 23);
        fillRemainder(name);
        Button settings = button(bar, "Ajustes", false, v -> openPreference(PreferenceScreen.SETTINGS));
        settings.setContentDescription("Ajustes de SlipTrain");

        scheduleCard = card(page, SURFACE);
        caption(scheduleCard, "PRÓXIMO VIAJE");
        scheduleStatus = title(scheduleCard, "", 30);
        scheduleDetail = text(scheduleCard, "", 14, MUTED);

        plannedCard = card(page, CARD);
        title(plannedCard, "Viaje ahora", 21);
        View destination = actionRow(plannedCard, "⌕", "Destino", display(destinationId, "Elegir destino"),
                () -> openStationPicker(false));
        destination.setEnabled(planner != null);
        planAction = button(plannedCard, "Iniciar viaje", true, v -> activateManual());
        chooseRouteAction = button(plannedCard, "Elegir ruta", false, v -> findManualRoutes());
        chooseRouteAction.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3387bf9c), null, background(CARD, 14)));
        chooseRouteAction.setTextColor(MUTED);
        planningDetail = text(plannedCard, "Las rutas aparecerán al detectar tu estación de salida.", 13, MUTED);
        cancelSearchAction = button(plannedCard, "Cancelar búsqueda", false, v -> { cancelLocationSearch(); updateLive(); });

        activeCard = card(page, CARD);
        activeCard.setVisibility(View.GONE);
        caption(activeCard, "VIAJE EN CURSO");
        nowStatus = title(activeCard, "", 24);
        nowProgress = text(activeCard, "", 15, MUTED);
        routeProgress = new RouteProgressView(this, MINT, MUTED, TEXT, SURFACE);
        activeCard.addView(routeProgress, new LinearLayout.LayoutParams(-1, -2));
        arrivalAction = button(activeCard, "Estoy despierto", true, v -> { JourneyActions.acknowledge(this); updateLive(); });
        transferAction = button(activeCard, "Ya estoy en el siguiente tren", true, v -> manualTransfer());
        incidentsAction = button(activeCard, "Ver incidencias", false, v -> {
            JSONObject current = sessions.load();
            String alerts = current == null ? "" : current.optString("alerts");
            alert("Incidencias", alerts.isEmpty() ? "Sin incidencias comunicadas para tu viaje." : alerts);
        });
        endAction = button(activeCard, "Finalizar viaje", false, v -> { JourneyActions.stop(this); updateLive(); });

        LinearLayout alarmsBar = row(page);
        ((LinearLayout.LayoutParams) alarmsBar.getLayoutParams()).topMargin = dp(16);
        TextView alarmsHeading = title(alarmsBar, "Alarmas recurrentes", 22);
        fillRemainder(alarmsHeading);
        button(alarmsBar, "+ Nueva", false, v -> {
            if (planner == null) return;
            cancelLocationSearch();
            draft = new Draft();
            draft.destinationId = destinationId;
            if (hasPreciseLocation()) {
                Location current = newestLocation();
                Place nearby = current == null ? null : planner.nearest(current.getLatitude(), current.getLongitude());
                if (nearby != null && !nearby.id.equals(draft.destinationId)) draft.originId = nearby.id;
            }
            render();
        }).setEnabled(planner != null);

        List<CommuteProfile> profiles = commutes.list();
        if (profiles.isEmpty()) text(page, "Guarda los trayectos que haces cada semana.", 15, MUTED);
        else for (CommuteProfile profile : profiles) profileCard(page, profile);
        if (profiles.stream().anyMatch(profile -> profile.enabled) && !CommuteScheduler.ready(this)) {
            button(page, "Permisos de las alarmas recurrentes", false, v -> permissionsForAutomation());
        }
        updateLive();
    }

    private void profileCard(LinearLayout page, CommuteProfile profile) {
        LinearLayout tile = card(page, CARD);
        LinearLayout top = row(tile);
        LinearLayout summary = column();
        top.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        summary.setMinimumHeight(dp(48));
        summary.setOnClickListener(v -> { draft = new Draft(profile); render(); });
        summary.setContentDescription("Editar alarma " + profileName(profile));
        title(summary, profileName(profile), 20);
        String hours = profile.returnMinute < 0 ? formatTime(profile.outboundMinute)
                : formatTime(profile.outboundMinute) + "  /  " + formatTime(profile.returnMinute);
        TextView hoursText = title(summary, hours, 27);
        hoursText.setTextColor(profile.enabled ? MINT : MUTED);
        text(summary, dayLabel(profile.daysMask), 13, MUTED);
        Switch enabled = toggle(top, "", profile.enabled);
        enabled.setContentDescription("Activar " + profileName(profile));
        enabled.setOnCheckedChangeListener((view, on) -> {
            try {
                commutes.save(profile.withEnabled(on));
                render();
            } catch (Exception failure) { notifyUser("No se pudo guardar el cambio."); render(); }
        });
    }

    private void updateLive() {
        if (activeCard == null) return;
        JSONObject session = sessions.load();
        showTravelCard(activeCard, session != null);
        showTravelCard(plannedCard, session == null);
        boolean searching = awaitingManualLocation;
        boolean canPlan = planner != null && planner.place(destinationId) != null;
        planAction.setEnabled(canPlan);
        planAction.setAlpha(canPlan ? 1f : .55f);
        chooseRouteAction.setText(searching ? getString(R.string.finding_location) : manualRoute.isEmpty() ? "Elegir ruta" : "Cambiar ruta");
        chooseRouteAction.setVisibility(canPlan ? View.VISIBLE : View.GONE);
        chooseRouteAction.setEnabled(!searching);
        planningDetail.setVisibility(awaitingManualLocation ? View.VISIBLE : View.GONE);
        cancelSearchAction.setVisibility(searching ? View.VISIBLE : View.GONE);
        List<CommuteProfile> profiles = commutes.list();
        scheduleCard.setVisibility(profiles.isEmpty() ? View.GONE : View.VISIBLE);
        CommuteScheduler.Occurrence next = CommuteScheduler.next(profiles, Instant.now(), ZoneId.systemDefault());
        if (next == null) {
            scheduleStatus.setText(R.string.no_departures_scheduled);
            scheduleDetail.setText(R.string.enable_routine_for_next_departure);
        } else {
            scheduleStatus.setText(display(next.destinationId, "Viaje recurrente"));
            scheduleDetail.setText(CommuteScheduler.ready(this) ? "Detección desde " + OCCURRENCE.format(next.when)
                    : "Completa los permisos para detectar la salida.");
        }
        if (session == null) return;
        try {
            String tripDestination = display(session.optString("destinationId"), SessionStore.destinationName(session));
            boolean waiting = SessionStore.WAITING.equals(session.optString("phase"));
            boolean waitingLocation = SessionStore.WAITING_LOCATION.equals(session.optString("phase"));
            boolean ringing = session.optBoolean("ringing");
            arrivalAction.setVisibility(ringing && !waitingLocation ? View.VISIBLE : View.GONE);
            transferAction.setVisibility(waiting ? View.VISIBLE : View.GONE);
            incidentsAction.setVisibility(waitingLocation ? View.GONE : View.VISIBLE);
            endAction.setText(waitingLocation ? R.string.cancel_alert : R.string.finish_journey);
            routeProgress.setVisibility(waitingLocation ? View.GONE : View.VISIBLE);
            if (waitingLocation) {
                nowStatus.setText(R.string.alert_prepared);
                nowProgress.setText(getString(R.string.waiting_for_route_location, tripDestination));
                return;
            }
            if (waiting) {
                List<Station> stations = SessionStore.upcomingStations(session);
                nowStatus.setText(R.string.waiting_next_train);
                nowProgress.setText(getString(R.string.waiting_transfer_detail,
                        display(stations.get(0).id, stations.get(0).name), tripDestination));
                updateRouteProgress(session, 0, tripDestination);
                return;
            }
            JourneyEngine engine = SessionStore.engine(session);
            List<Station> stations = SessionStore.stations(session);
            int index = Math.min(stations.size() - 1, (int) engine.position());
            boolean transfer = SessionStore.hasTransfer(session);
            Station target = transfer ? SessionStore.interchange(session) : engine.alertStation();
            String targetName = display(target.id, target.name);
            boolean rerouted = SystemClock.elapsedRealtime() - session.optLong("reroutedElapsed", -20_000) < 15_000;
            nowStatus.setText(ringing || session.optBoolean("transferAcknowledged")
                    ? getString(transfer ? R.string.change_at : R.string.wake_at, targetName)
                    : rerouted ? "Ruta actualizada" : getString(R.string.on_route));
            String position = engine.gpsFresh(SystemClock.elapsedRealtime()) ? "GPS · " : "Última referencia · ";
            nowProgress.setText(getString(R.string.progress_detail, position, session.optString("lineName"), tripDestination));
            updateRouteProgress(session, index, tripDestination);
        } catch (Exception invalid) {
            nowStatus.setText(R.string.trip_in_progress);
            nowProgress.setText(R.string.trip_progress_unavailable);
        }
    }

    private void showTravelCard(View card, boolean visible) {
        if (card.getVisibility() == (visible ? View.VISIBLE : View.GONE)) return;
        card.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) ViewMotion.enter(card, 4);
    }

    private void updateRouteProgress(JSONObject session, int index, String destination) throws Exception {
        org.json.JSONArray legs = session.getJSONArray("legs");
        int current = index;
        List<String> stops = new ArrayList<>();
        for (int leg = 0; leg < legs.length(); leg++) {
            List<Station> stations = SessionStore.stations(legs.getJSONObject(leg));
            if (leg < session.getInt("legIndex")) current += stations.size() - 1;
            for (int i = leg == 0 ? 0 : 1; i < stations.size(); i++) {
                Station station = stations.get(i);
                stops.add(display(station.id, station.name));
            }
        }
        stops.set(stops.size() - 1, destination);
        routeProgress.show(stops, current, preferences.getBoolean("verticalProgress", false));
    }

    private void editor(LinearLayout page) {
        subpageHeader(page, draft.id.isEmpty() ? "Nueva alarma" : "Editar alarma");

        LinearLayout route = card(page, CARD);
        title(route, "Trayecto", 18);
        actionRow(route, "↕", "Desde y hasta", stationsSummary(), () -> openStationPicker(true)).setEnabled(planner != null);
        if (draftStationsReady()) {
            CharSequence summary = draftRouteLabel(false);
            if (draft.returnMinute >= 0) {
                summary = new SpannableStringBuilder("Ida  ").append(summary).append("\nVuelta  ").append(draftRouteLabel(true));
            }
            actionRow(route, "⇄", "Cambiar rutas", summary, () -> chooseDraftRoutes(false));
        }

        LinearLayout times = card(page, CARD);
        LinearLayout timeRow = row(times);
        timeTile(timeRow, "Ida", draft.outboundMinute, minutes -> { draft.outboundMinute = minutes; render(); });
        if (draft.returnMinute >= 0) {
            View space = new View(this);
            timeRow.addView(space, new LinearLayout.LayoutParams(dp(12), 1));
            timeTile(timeRow, "Vuelta", draft.returnMinute, minutes -> { draft.returnMinute = minutes; render(); });
        }
        Switch returnSwitch = toggle(times, "Incluir vuelta", draft.returnMinute >= 0);
        returnSwitch.setOnCheckedChangeListener((view, on) -> {
            draft.returnMinute = on ? 14 * 60 : -1;
            if (on && draftStationsReady() && draft.returnRoute.isEmpty()) chooseDraftRoutes(true);
            else render();
        });

        LinearLayout options = card(page, CARD);
        text(options, "Repetir", 14, MUTED);
        button(options, dayLabel(draft.days), false, v -> chooseDays());
        actionRow(options, "♫", "Audio", AlarmAudio.name(this, draft.audioId), () -> openAudio(true));
        Switch vibration = toggle(options, "Vibración", draft.vibration);
        vibration.setOnCheckedChangeListener((view, on) -> draft.vibration = on);
        CheckBox transferAlarm = transferOption(options, draft.transferAlarm);
        transferAlarm.setOnCheckedChangeListener((view, on) -> draft.transferAlarm = on);
        text(options, "Etiqueta (opcional)", 14, MUTED);
        labelEditor = new EditText(this);
        labelEditor.setSingleLine();
        labelEditor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        labelEditor.setText(draft.label);
        labelEditor.setHint(display(draft.destinationId, "Nombre del destino"));
        options.addView(labelEditor, new LinearLayout.LayoutParams(-1, -2));

        button(page, "Guardar alarma", true, v -> saveDraft()).setEnabled(planner != null);
        if (!draft.id.isEmpty()) button(page, "Eliminar alarma", false, v -> new AlertDialog.Builder(this)
                .setTitle("¿Eliminar esta alarma recurrente?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar", (dialog, which) -> {
                    try { commutes.remove(draft.id); draft = null; render(); }
                    catch (Exception failure) { notifyUser("No se pudo eliminar la alarma."); }
                }).show());
    }

    private CheckBox transferOption(LinearLayout parent, boolean enabled) {
        CheckBox control = new CheckBox(this);
        control.setText(R.string.transfer_alarm);
        control.setTextColor(TEXT);
        control.setChecked(enabled);
        control.setMinHeight(dp(48));
        parent.addView(control);
        return control;
    }

    private boolean draftStationsReady() {
        return planner != null && planner.place(draft.originId) != null && planner.place(draft.destinationId) != null
                && planner.place(draft.originId) != planner.place(draft.destinationId);
    }

    private String stationsSummary() {
        if (!draftStationsReady()) return "Elegir salida y destino";
        return display(draft.originId, "") + " → " + display(draft.destinationId, "");
    }

    private CharSequence draftRouteLabel(boolean returning) {
        String key = returning ? draft.returnRoute : draft.outboundRoute;
        if (key.isEmpty()) return "Elegir ruta";
        try {
            return lineChain(planner.restore(key, returning ? draft.destinationId : draft.originId,
                    returning ? draft.originId : draft.destinationId, 1));
        } catch (IllegalArgumentException unavailable) { return "Elegir otra ruta"; }
    }

    private void chooseDraftRoutes(boolean returning) {
        RouteSelection selection = new RouteSelection(draft.originId, draft.destinationId, 1,
                false, draft.returnMinute >= 0, draft.outboundRoute, draft.returnRoute, false);
        selection.returning = returning;
        openRoutes(selection);
    }

    private void timeTile(LinearLayout parent, String label, int minutes, Consumer<Integer> update) {
        LinearLayout tile = column();
        tile.setPadding(dp(14), dp(12), dp(14), dp(12));
        tile.setBackground(background(SURFACE, 16));
        parent.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
        text(tile, label, 13, MUTED);
        title(tile, formatTime(minutes), 27).setTextColor(MINT);
        tile.setContentDescription(label + " " + formatTime(minutes) + ", cambiar hora");
        tile.setMinimumHeight(dp(64));
        tile.setOnClickListener(v -> new TimePickerDialog(this, (picker, hour, minute) -> {
            syncDraft();
            update.accept(hour * 60 + minute);
        }, minutes / 60, minutes % 60, true).show());
    }

    private void chooseDays() {
        String[] choices = {"Lunes a viernes", "Lunes, miércoles y viernes", "Sábado y domingo", "Todos los días", "Elegir días"};
        int[] presets = {CommuteProfile.DAYS_WEEKDAYS, CommuteProfile.DAYS_MWF, CommuteProfile.DAYS_WEEKEND, CommuteProfile.DAYS_EVERY_DAY};
        new AlertDialog.Builder(this).setTitle("Repetir alarma").setItems(choices, (dialog, index) -> {
            if (index < presets.length) { draft.days = presets[index]; render(); return; }
            boolean[] checked = new boolean[7];
            for (int i = 0; i < 7; i++) checked[i] = (draft.days & (1 << i)) != 0;
            new AlertDialog.Builder(this).setTitle("Días de viaje").setMultiChoiceItems(DAYS, checked, (d, i, on) -> checked[i] = on)
                    .setNegativeButton("Cancelar", null).setPositiveButton("Aplicar", (d, button) -> {
                        int mask = 0;
                        for (int i = 0; i < 7; i++) if (checked[i]) mask |= 1 << i;
                        if (mask == 0) { notifyUser("Selecciona al menos un día."); return; }
                        draft.days = mask;
                        render();
                    }).show();
        }).show();
    }

    private void saveDraft() {
        syncDraft();
        if (planner.place(draft.originId) == null || planner.place(draft.destinationId) == null) {
            notifyUser("Elige la estación de salida y el destino.");
            return;
        }
        try {
            if (draft.outboundRoute.isEmpty()) throw new IllegalArgumentException("Elige la ruta de ida.");
            planner.restore(draft.outboundRoute, draft.originId, draft.destinationId, 1);
            if (draft.returnMinute >= 0) {
                if (draft.returnMinute == draft.outboundMinute) throw new IllegalArgumentException("Elige horas distintas para la ida y la vuelta.");
                if (draft.returnRoute.isEmpty()) throw new IllegalArgumentException("Elige la ruta de vuelta.");
                planner.restore(draft.returnRoute, draft.destinationId, draft.originId, 1);
            }
            commutes.save(new CommuteProfile(draft.id, draft.originId, draft.destinationId, draft.outboundMinute,
                    draft.returnMinute, draft.days, draft.label, draft.audioId, draft.vibration, draft.enabled,
                    draft.transferAlarm, draft.outboundRoute, draft.returnRoute));
            draft = null;
            render();
        } catch (IllegalArgumentException invalid) { notifyUser(invalid.getMessage()); }
        catch (Exception failure) { notifyUser("No se pudo guardar la alarma."); }
    }

    private void openStationPicker(boolean recurring) {
        if (planner == null) return;
        if (!recurring) cancelLocationSearch();
        stationPicker = new StationPicker(recurring, recurring ? draft.originId : "",
                recurring ? draft.destinationId : destinationId);
        render();
    }

    private void stationsPage(LinearLayout frame, StationPicker picker) {
        boolean choosingOrigin = picker.choosingOrigin;
        LinearLayout page = prepareSubpage(frame, picker.recurring ? "Desde y hasta" : "Destino");
        if (picker.recurring) {
            LinearLayout endpoints = card(page, CARD);
            pickerEndpoint(endpoints, picker, true);
            pickerEndpoint(endpoints, picker, false);
        }

        LinearLayout field = row(page);
        field.setBackground(outlined(SURFACE, EDGE, 14));
        field.setPadding(dp(13), 0, dp(12), 0);
        ((LinearLayout.LayoutParams) field.getLayoutParams()).topMargin = dp(12);
        text(field, "⌕", 23, MINT).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setTextSize(16);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        input.setPadding(dp(8), 0, 0, 0);
        input.setHint(choosingOrigin ? "Buscar estación de salida" : "Buscar destino");
        input.setText(picker.query);
        input.setMinHeight(dp(52));
        field.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        title(page, "Estaciones", 18).setPadding(0, dp(16), 0, dp(8));
        ListView results = new ListView(this);
        results.setDivider(null);
        results.setSelector(android.R.color.transparent);
        results.setClipToPadding(false);
        results.setPadding(0, 0, 0, dp(8));
        results.setVerticalScrollBarEnabled(false);
        page.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView empty = text(page, planner == null ? "Cargando estaciones…" : "No hay estaciones con ese nombre.", 14, MUTED);
        results.setEmptyView(empty);
        if (planner == null) return;

        ArrayAdapter<Place> adapter = new ArrayAdapter<Place>(this, android.R.layout.simple_list_item_1) {
            @Override public View getView(int position, View reused, android.view.ViewGroup parent) {
                Place place = getItem(position);
                LinearLayout wrapper = column();
                wrapper.setPadding(0, 0, 0, dp(7));
                LinearLayout item = row(wrapper);
                item.setMinimumHeight(dp(62));
                item.setPadding(dp(13), dp(6), dp(12), dp(6));
                item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3387bf9c), outlined(CARD, EDGE, 14), null));
                List<String> lines = planner.lineNames(place);
                LinearLayout values = column();
                item.addView(values, new LinearLayout.LayoutParams(0, -2, 1));
                title(values, place.name, 16);
                text(values, String.join(" · ", lines.stream().map(MainActivity::lineCode).toArray(String[]::new)), 13, MUTED)
                        .setPadding(0, 0, dp(8), dp(4));
                if (lines.stream().anyMatch(line -> line.startsWith("Metro"))) operator(item, true);
                if (lines.stream().anyMatch(line -> line.startsWith("Cercanías"))) operator(item, false);
                wrapper.setContentDescription(place.name + ", " + String.join(", ", lines));
                return wrapper;
            }
        };
        results.setAdapter(adapter);
        Place excluded = planner.place(choosingOrigin ? picker.destination : picker.origin);
        Consumer<String> filter = query -> {
            String needle = normalized(query);
            List<Place> prefix = new ArrayList<>(), contains = new ArrayList<>();
            for (Place place : planner.destinations()) {
                if (place == excluded) continue;
                String name = normalized(place.name);
                if (name.startsWith(needle)) prefix.add(place);
                else if (name.contains(needle)) contains.add(place);
            }
            adapter.setNotifyOnChange(false);
            adapter.clear();
            adapter.addAll(prefix);
            adapter.addAll(contains);
            adapter.notifyDataSetChanged();
            results.setSelection(0);
        };
        filter.accept(picker.query);
        results.setOnItemClickListener((parent, view, index, id) -> selectStation(picker, adapter.getItem(index)));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                picker.query = s.toString();
                filter.accept(picker.query);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        if (picker.focusSearch) {
            picker.focusSearch = false;
            input.requestFocus();
            input.post(() -> {
                if (stationPicker == picker && input.isAttachedToWindow()) {
                    getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
    }

    private void pickerEndpoint(LinearLayout endpoints, StationPicker picker, boolean origin) {
        boolean editing = picker.choosingOrigin == origin;
        String label = origin ? "Desde" : "Hasta";
        View endpoint = actionRow(endpoints, origin ? "○" : "●", label,
                display(origin ? picker.origin : picker.destination, origin ? "Elegir salida" : "Elegir destino"), () -> {
                    picker.choosingOrigin = origin;
                    picker.query = "";
                    picker.focusSearch = true;
                    render();
                });
        if (editing) endpoint.setBackground(selectable(true, 13));
    }

    private void selectStation(StationPicker picker, Place place) {
        if (place == null || stationPicker != picker) return;
        if (picker.choosingOrigin) {
            picker.origin = place.id;
            if (picker.destinationChosen) {
                finishStationSelection(picker, planner.place(picker.destination));
                return;
            }
            picker.choosingOrigin = false;
            picker.query = "";
            View focused = getCurrentFocus();
            picker.focusSearch = focused instanceof EditText;
            render();
            return;
        }
        if (picker.recurring && planner.place(picker.origin) == null) {
            picker.destination = place.id;
            picker.destinationChosen = true;
            picker.choosingOrigin = true;
            picker.query = "";
            View focused = getCurrentFocus();
            picker.focusSearch = focused instanceof EditText;
            render();
            return;
        }
        finishStationSelection(picker, place);
    }

    private void finishStationSelection(StationPicker picker, Place place) {
        hideKeyboard();
        stationPicker = null;
        if (picker.recurring) {
            if (planner.place(draft.originId) != planner.place(picker.origin)
                    || planner.place(draft.destinationId) != place) {
                draft.outboundRoute = draft.returnRoute = "";
            }
            draft.originId = picker.origin;
            draft.destinationId = place.id;
            chooseDraftRoutes(false);
        } else {
            destinationId = place.id;
            preferences.edit().putString("lastDestination", destinationId).apply();
            render();
        }
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private void findManualRoutes() {
        if (planner == null || planner.place(destinationId) == null || sessions.load() != null || awaitingManualLocation || hasSubpage()) return;
        manualStartRequested = false;
        if (!hasPreciseLocation()) {
            askPrecise(PRECISE);
            return;
        }
        if (planner.place(manualOrigin) != null) {
            openManualRoutes();
            return;
        }
        if (!locationEnabled()) {
            new AlertDialog.Builder(this).setTitle("Activa la ubicación")
                    .setMessage("SlipTrain busca tu estación de salida con la ubicación del móvil.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Abrir ajustes", (d, i) -> {
                        waitingForLocationSettings = true;
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    }).show();
            return;
        }
        awaitingManualLocation = true;
        updateLive();
        listenForOrigin();
    }

    private void listenForOrigin() {
        if (planner == null || locationListener != null || !hasPreciseLocation() || draft != null) return;
        if (sessions.load() != null) { cancelLocationSearch(); return; }
        String target = destinationId;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { beginWithLocation(location, target); }
            @Override public void onProviderEnabled(String provider) { requestOriginProvider(provider); }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        };
        for (String provider : locationManager.getAllProviders()) requestOriginProvider(provider);
        Location newest = newestLocation();
        if (newest != null && locationListener != null) locationListener.onLocationChanged(newest);
    }

    private void requestOriginProvider(String provider) {
        if (locationListener == null || LocationManager.PASSIVE_PROVIDER.equals(provider)) return;
        try { locationManager.requestLocationUpdates(provider, 1_000, 0, locationListener, Looper.getMainLooper()); }
        catch (SecurityException | IllegalArgumentException ignored) { }
    }

    private void beginWithLocation(Location location, String target) {
        if (!awaitingManualLocation || preferenceScreen != null || !destinationId.equals(target) || !freshLocation(location)) return;
        if (sessions.load() != null) { cancelLocationSearch(); updateLive(); return; }
        Place origin = planner.nearest(location.getLatitude(), location.getLongitude());
        if (origin == null) return;
        if (origin == planner.place(target)) {
            cancelLocationSearch();
            updateLive();
            notifyUser("Ya estás en el destino.");
            return;
        }
        detachOriginListener();
        awaitingManualLocation = false;
        if (!origin.id.equals(manualOrigin)) manualRoute = "";
        manualOrigin = origin.id;
        openManualRoutes();
    }

    private void openManualRoutes() {
        openRoutes(new RouteSelection(manualOrigin, destinationId, SessionStore.configuredAdvance(this),
                true, false, manualRoute, "", preferences.getBoolean(TRANSFER_ALARM, true)));
    }

    private void activateManual() {
        if (planner == null || planner.place(destinationId) == null || sessions.load() != null || hasSubpage()) return;
        manualStartRequested = true;
        if (!hasNotifications()) { askNotifications(NOTIFICATIONS); return; }
        if (!AlarmScheduler.allowed(this)) {
            new AlertDialog.Builder(this).setTitle("Permitir alarmas")
                    .setMessage("Android necesita este permiso para avisarte con la pantalla apagada.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Abrir ajustes", (d, i) -> {
                        waitingForExactAlarm = true;
                        exactAlarmSettings();
                    }).show();
            return;
        }
        if (!hasPreciseLocation()) { askPrecise(PRECISE); return; }
        if (!locationEnabled()) {
            new AlertDialog.Builder(this).setTitle("Activa la ubicación")
                    .setMessage("El aviso seguirá esperando tu ubicación para iniciar el seguimiento.")
                    .setPositiveButton("Entendido", null).show();
        }
        try {
            detachOriginListener();
            awaitingManualLocation = false;
            int advance = SessionStore.configuredAdvance(this);
            Location fix = newestLocation();
            JourneyPlan plan = null;
            if (!manualRoute.isEmpty()) plan = planner.restore(manualRoute, manualOrigin, destinationId, advance);
            else if (fix != null) {
                Place origin = planner.nearest(fix.getLatitude(), fix.getLongitude(), 150);
                if (origin == planner.place(destinationId)) throw new IllegalArgumentException("Ya estás en el destino.");
                if (origin != null) {
                    try { plan = planner.plan(origin.id, destinationId, advance); }
                    catch (IllegalArgumentException error) { /* Wait for a usable departure station. */ }
                }
            }
            JSONObject session = sessions.createManual(plan, preferences.getBoolean("vibration", true),
                    preferences.getString("audioId", ""), destinationId, display(destinationId, ""), advance, fix,
                    preferences.getBoolean(TRANSFER_ALARM, true));
            try {
                AlarmScheduler.arm(this, session);
                startForegroundService(new Intent(this, TripService.class));
            } catch (RuntimeException failure) {
                JourneyActions.stop(this);
                throw failure;
            }
            cancelLocationSearch();
            render();
        } catch (IllegalArgumentException unavailable) {
            cancelLocationSearch();
            render();
            notifyUser(unavailable.getMessage());
        } catch (Exception failure) {
            cancelLocationSearch();
            render();
            notifyUser("No se pudo activar el aviso.");
        }
    }

    private Location newestLocation() {
        Location newest = null;
        for (String provider : locationManager.getProviders(true)) {
            try {
                Location last = locationManager.getLastKnownLocation(provider);
                if (freshLocation(last) && (newest == null || last.getElapsedRealtimeNanos() > newest.getElapsedRealtimeNanos())) newest = last;
            } catch (SecurityException | IllegalArgumentException ignored) { }
        }
        return newest;
    }

    private boolean freshLocation(Location location) {
        if (location == null || !location.hasAccuracy() || location.getAccuracy() <= 0 || location.getAccuracy() > 80) return false;
        long age = SystemClock.elapsedRealtime() - location.getElapsedRealtimeNanos() / 1_000_000;
        return age >= 0 && age <= 15_000;
    }

    private void openRoutes(RouteSelection selection) {
        routeSelection = selection;
        render();
        loadRoutes(selection);
    }

    private void loadRoutes(RouteSelection selection) {
        AutomaticRoutePlanner network = planner;
        worker.execute(() -> {
            try {
                List<JourneyPlan> outbound = candidates(network, selection.origin, selection.destination,
                        selection.advance, selection.outboundKey);
                List<JourneyPlan> inbound = selection.roundTrip
                        ? candidates(network, selection.destination, selection.origin, selection.advance, selection.returnKey)
                        : new ArrayList<>();
                main.post(() -> {
                    if (!showing(selection)) return;
                    selection.outbound = estimates(outbound);
                    selection.inbound = estimates(inbound);
                    selection.checkingLive = selection.manual && outbound.stream().anyMatch(route ->
                            route.legs.stream().anyMatch(leg -> leg.line.realtimeAvailable));
                    updateRoutePage(selection);
                    if (selection.checkingLive) loadRealtimeRoutes(selection, outbound);
                });
            } catch (IllegalArgumentException unavailable) {
                main.post(() -> {
                    if (!showing(selection)) return;
                    selection.error = unavailable.getMessage();
                    updateRoutePage(selection);
                });
            }
        });
    }

    private List<JourneyPlan> candidates(AutomaticRoutePlanner network, String from, String to, int advance, String saved) {
        List<JourneyPlan> routes = new ArrayList<>(network.routeCandidates(from, to, advance));
        if (!saved.isEmpty() && routes.stream().noneMatch(plan -> plan.routeKey().equals(saved))) {
            try { routes.add(network.restore(saved, from, to, advance)); }
            catch (IllegalArgumentException unavailable) { /* Offer the routes in the current catalogue. */ }
        }
        return routes;
    }

    private List<Option> estimates(List<JourneyPlan> plans) {
        List<Option> options = new ArrayList<>();
        for (JourneyPlan plan : plans) options.add(RouteEstimates.estimate(plan));
        return options;
    }

    private boolean showing(RouteSelection selection) {
        return !isFinishing() && !isDestroyed() && routeSelection == selection;
    }

    private void loadRealtimeRoutes(RouteSelection selection, List<JourneyPlan> outbound) {
        liveRoutes.execute(() -> {
            Map<String, TransitLine> schedules = new HashMap<>();
            TransitRepository repository = new TransitRepository(this);
            for (JourneyPlan plan : outbound) {
                for (JourneyPlan.Leg leg : plan.legs) {
                    if (leg.line.realtimeAvailable) schedules.computeIfAbsent(leg.line.id, ignored -> {
                        try { return repository.loadRealtimeLine(leg.line); }
                        catch (Exception unavailable) { return leg.line; }
                    });
                }
            }
            planningFeed.refreshForPlanning();
            RouteEstimates estimates = new RouteEstimates(schedules, planningFeed, System.currentTimeMillis());
            List<Option> updated = new ArrayList<>();
            for (JourneyPlan plan : outbound) updated.add(estimates.atDeparture(plan));
            main.post(() -> {
                if (!showing(selection)) return;
                selection.outbound = updated;
                selection.checkingLive = false;
                updateRoutePage(selection);
            });
        });
    }

    private void routesPage(LinearLayout frame, RouteSelection selection) {
        LinearLayout page = prepareSubpage(frame, selection.manual ? "Elegir ruta" : "Rutas del trayecto");
        if (selection.roundTrip) {
            LinearLayout tabs = row(page);
            tabs.setPadding(0, dp(8), 0, 0);
            routeTab(tabs, selection, false);
            routeTab(tabs, selection, true);
        }
        LinearLayout controls = row(page);
        controls.setPadding(0, dp(8), 0, 0);
        boolean returning = selection.returning;
        Button sort = button(controls, "Ordenar", false, view -> routeMenu(view,
                new String[]{"Recomendadas", "Menor tiempo de viaje", "Menos transbordos"}, selection.order(returning).ordinal(), choice -> {
                    selection.orders[returning ? 1 : 0] = Order.values()[choice];
                    selection.startListAtTop = true;
                    updateRoutePage(selection);
                }));
        Button filter = button(controls, "Filtrar", false, view -> routeMenu(view,
                new String[]{"Todos los operadores", "Solo Metro", "Solo Cercanías"}, selection.operator(returning).ordinal(), choice -> {
                    selection.operators[returning ? 1 : 0] = Operator.values()[choice];
                    selection.startListAtTop = true;
                    updateRoutePage(selection);
                }));
        fillRemainder(sort);
        fillRemainder(filter);
        ((LinearLayout.LayoutParams) filter.getLayoutParams()).setMarginStart(dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout options = column();
        options.setPadding(0, 0, 0, dp(8));
        scroll.addView(options);
        Button confirm = button(page, selection.roundTrip ? "Usar estas rutas" : "Usar esta ruta",
                true, view -> confirmRoutes(selection));
        selection.redraw = () -> {
            int position = selection.startListAtTop ? 0 : scroll.getScrollY();
            selection.startListAtTop = false;
            if (position == 0) scroll.scrollTo(0, 0);
            options.removeAllViews();
            LinearLayout endpoints = card(options, 0xff173326);
            text(endpoints, "Desde " + display(returning ? selection.destination : selection.origin, ""), 14, MUTED);
            title(endpoints, display(returning ? selection.origin : selection.destination, ""), 24);

            boolean expanded = selection.expanded(returning);
            List<Option> choices = selection.choices(returning, expanded);
            boolean filtered = selection.operator(returning) != Operator.ALL;
            sort.setTextColor(selection.order(returning) == Order.RECOMMENDED ? TEXT : MINT);
            filter.setTextColor(filtered ? MINT : TEXT);
            confirm.setEnabled(!selection.options(false).isEmpty() && (!selection.roundTrip || !selection.options(true).isEmpty()));
            confirm.setAlpha(confirm.isEnabled() ? 1f : .55f);
            if (choices.isEmpty()) {
                boolean loaded = !(returning ? selection.inbound : selection.outbound).isEmpty();
                String message = loaded ? "No hay rutas con este filtro."
                        : selection.error.isEmpty() ? getString(R.string.calculating_routes) : selection.error;
                text(options, message, 14, MUTED)
                        .setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                return;
            }
            TextView heading = title(options, "Rutas disponibles", 18);
            heading.setPadding(0, dp(16), 0, 0);
            text(options, routeTimingLabel(selection, choices), 13, MUTED).setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            Option selected = selection.selected(choices, returning);
            List<View> cards = new ArrayList<>();
            List<Option> recommendations = expanded && selection.order(returning) == Order.RECOMMENDED ? selection.choices(returning, false) : choices;
            for (Option choice : recommendations) cards.add(routeCard(options, selection, choice, choice.equals(selected), cards));
            if (selection.manual) {
                CheckBox transfer = transferOption(options, selection.transferAlarm);
                transfer.setOnCheckedChangeListener((view, checked) -> selection.transferAlarm = checked);
            }
            if (selection.options(returning).size() > recommendations.size()) {
                Button more = button(options, expanded ? "Mostrar menos" : "Más rutas", false, view -> {
                    selection.setExpanded(returning, !expanded);
                    selection.animateExpansion = !expanded;
                    updateRoutePage(selection);
                });
                more.setSelected(expanded);
            }
            if (expanded && choices.size() > recommendations.size()) {
                LinearLayout additional = column();
                options.addView(additional, new LinearLayout.LayoutParams(-1, -2));
                for (Option choice : choices) if (!recommendations.contains(choice)) {
                    cards.add(routeCard(additional, selection, choice, choice.equals(selected), cards));
                }
                if (selection.animateExpansion) ViewMotion.enter(additional, 4);
                selection.animateExpansion = false;
            }
            if (position != 0) scroll.post(() -> scroll.scrollTo(0, position));
        };
        updateRoutePage(selection);
    }

    private void routeMenu(View anchor, String[] entries, int current, Consumer<Integer> onSelected) {
        PopupMenu menu = new PopupMenu(this, anchor);
        for (int i = 0; i < entries.length; i++) menu.getMenu().add(1, i, i, entries[i]);
        menu.getMenu().setGroupCheckable(1, true, true);
        menu.getMenu().findItem(current).setChecked(true);
        menu.setOnMenuItemClickListener(item -> {
            onSelected.accept(item.getItemId());
            return true;
        });
        menu.show();
    }

    private void updateRoutePage(RouteSelection selection) {
        if (selection.redraw != null) selection.redraw.run();
    }

    private void routeTab(LinearLayout tabs, RouteSelection selection, boolean returning) {
        Button tab = button(tabs, returning ? "Vuelta" : "Ida", false, view -> {
            if (selection.returning == returning) return;
            selection.returning = returning;
            render();
        });
        tab.setTextColor(selection.returning == returning ? MINT : MUTED);
        tab.setSelected(selection.returning == returning);
        tab.setBackground(selectable(selection.returning == returning, 13));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (returning) params.setMarginStart(dp(8));
        tab.setLayoutParams(params);
    }

    private String routeTimingLabel(RouteSelection selection, List<Option> choices) {
        if (selection.checkingLive) return "Comprobando horarios e incidencias de Cercanías…";
        boolean timed = choices.stream().anyMatch(option -> option.status() == RouteEstimates.Status.LIVE
                || option.status() == RouteEstimates.Status.SCHEDULE);
        if (!timed) return "Duraciones orientativas · sin espera de tren";
        boolean metro = choices.stream().anyMatch(option -> option.plan().legs.stream().anyMatch(leg -> !leg.line.realtimeAvailable));
        return metro ? "Estimaciones con horarios de Cercanías · Metro sin espera" : "Estimaciones con horarios de Cercanías";
    }

    private View routeCard(LinearLayout options, RouteSelection selection, Option choice, boolean selected, List<View> cards) {
        JourneyPlan route = choice.plan();
        LinearLayout card = card(options, CARD);
        ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin = dp(8);
        card.setPadding(dp(13), dp(8), dp(13), dp(8));
        card.setBackground(selectable(selected, 16));
        card.setSelected(selected);
        LinearLayout headline = row(card);
        int minutes = (choice.seconds() + 59) / 60;
        TextView chain = title(headline, "", 15);
        chain.setText(lineChain(route));
        fillRemainder(chain);
        TextView duration = title(headline, choice.unavailable() ? "—" : minutes + " min", 16);
        duration.setPadding(dp(8), dp(4), 0, dp(4));

        int changes = route.legs.size() - 1;
        String transfers = changes == 0 ? getString(R.string.no_transfers)
                : getResources().getQuantityString(R.plurals.transfer_count, changes, changes);
        text(card, transfers, 13, MUTED);
        if (changes > 0) {
            List<String> stops = new ArrayList<>();
            for (int leg = 1; leg < route.legs.size(); leg++) {
                Station station = route.legs.get(leg).stations.get(0);
                stops.add(display(station.id, station.name));
            }
            text(card, getString(R.string.change_at, String.join(" · ", stops)), 13, MUTED);
        }
        switch (choice.status()) {
            case LIVE -> text(card, "Cercanías en tiempo real", 13, MINT);
            case NO_DEPARTURE -> text(card, "Sin enlace previsto en Cercanías", 13, GOLD);
            case DISRUPTION -> text(card, "Cercanías · servicio afectado", 13, GOLD);
            default -> { }
        }
        card.setFocusable(true);
        if (Build.VERSION.SDK_INT >= 28) card.setScreenReaderFocusable(true);
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.RadioButton.class.getName());
                info.setCheckable(true);
                info.setChecked(host.isSelected());
            }
        });
        card.setOnClickListener(view -> {
            if (selection.returning) selection.returnKey = route.routeKey(); else selection.outboundKey = route.routeKey();
            for (View option : cards) {
                option.setSelected(option == view);
                option.setBackground(selectable(option == view, 16));
            }
        });
        return card;
    }

    private void confirmRoutes(RouteSelection selection) {
        Option outbound = selection.selected(selection.choices(false, selection.expanded(false)), false);
        if (outbound == null) return;
        if (selection.manual) {
            preferences.edit().putBoolean(TRANSFER_ALARM, selection.transferAlarm).apply();
            manualRoute = outbound.plan().routeKey();
            routeSelection = null;
            render();
        } else {
            Option inbound = selection.selected(selection.choices(true, selection.expanded(true)), true);
            if (selection.roundTrip && inbound == null) return;
            draft.outboundRoute = outbound.plan().routeKey();
            if (inbound != null) draft.returnRoute = inbound.plan().routeKey();
            routeSelection = null;
            render();
        }
    }

    private static String lineCode(String name) {
        int separator = name.lastIndexOf(" · ");
        return separator < 0 ? name : name.substring(separator + 3);
    }

    private void operator(LinearLayout parent, boolean metro) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(metro ? R.drawable.operator_metro : R.drawable.operator_renfe);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(metro ? 28 : 20), dp(22));
        size.setMarginStart(dp(6));
        parent.addView(icon, size);
    }

    private CharSequence lineChain(JourneyPlan route) {
        SpannableStringBuilder value = new SpannableStringBuilder();
        for (JourneyPlan.Leg leg : route.legs) {
            if (value.length() > 0) value.append(" › ");
            String code = lineCode(leg.line.name);
            int start = value.length();
            value.append(code);
            int color = switch (code) {
                case "L3", "L4" -> GOLD;
                case "L10" -> 0xffd0c8ff;
                default -> MINT;
            };
            int fill = switch (code) {
                case "L3", "L4" -> 0xff493a26;
                case "L10" -> 0xff37354b;
                default -> 0xff254736;
            };
            value.setSpan(new LineBadge(color, fill), start, value.length(), 0);
        }
        return value;
    }

    private final class LineBadge extends ReplacementSpan {
        private final int color, fill;

        LineBadge(int color, int fill) { this.color = color; this.fill = fill; }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            return (int) Math.ceil(paint.measureText(text, start, end)) + dp(12);
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            int previous = paint.getColor();
            paint.setColor(fill);
            canvas.drawRoundRect(x, y + paint.ascent(), x + getSize(paint, text, start, end, null), y + paint.descent(),
                    dp(6), dp(6), paint);
            paint.setColor(color);
            canvas.drawText(text, start, end, x + dp(6), y, paint);
            paint.setColor(previous);
        }
    }

    private void cancelLocationSearch() {
        detachOriginListener();
        awaitingManualLocation = waitingForLocationSettings = waitingForExactAlarm = manualStartRequested = false;
        manualOrigin = manualRoute = "";
    }

    private void detachOriginListener() {
        if (locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (RuntimeException ignored) { }
            locationListener = null;
        }
    }

    private void manualTransfer() {
        if (!AlarmScheduler.allowed(this)) { exactAlarmSettings(); return; }
        try {
            JSONObject next = sessions.continueJourney(newestLocation());
            AlarmScheduler.arm(this, next);
            startForegroundService(new Intent(this, TripService.class));
            updateLive();
        } catch (Exception error) { notifyUser("No se pudo continuar el viaje."); }
    }

    private void permissionsForAutomation() {
        if (!hasNotifications()) {
            askNotifications(BACKGROUND + 10);
        } else if (!hasPreciseLocation()) {
            askPrecise(AUTO_PRECISE);
        } else if (!CommuteScheduler.hasBackgroundLocation(this)) {
            if (Build.VERSION.SDK_INT == 29) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BACKGROUND);
            } else {
                String choice = getPackageManager().getBackgroundPermissionOptionLabel().toString();
                new AlertDialog.Builder(this).setTitle("Detectar salidas con la pantalla apagada")
                        .setMessage("En los ajustes de ubicación de SlipTrain, elige «" + choice
                                + "». La app buscará movimiento cuando llegue la hora programada.")
                        .setNegativeButton("Ahora no", null).setPositiveButton("Abrir ajustes", (d, i) -> appSettings()).show();
            }
        } else if (!locationEnabled()) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else if (!AlarmScheduler.allowed(this)) {
            exactAlarmSettings();
        } else {
            CommuteScheduler.refresh(this);
            render();
        }
    }

    private boolean hasNotifications() {
        return getSystemService(NotificationManager.class).areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    private void askNotifications(int code) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, code);
        } else appSettings();
    }

    private boolean hasPreciseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPrecise(int code) {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, code);
    }

    private boolean locationEnabled() {
        return Build.VERSION.SDK_INT >= 28 ? locationManager.isLocationEnabled()
                : locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private void exactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= 31) {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
        }
    }

    private void appSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    @Override public void onRequestPermissionsResult(int code, String[] names, int[] grants) {
        super.onRequestPermissionsResult(code, names, grants);
        if (code == PRECISE || code == NOTIFICATIONS) {
            if (code == PRECISE && hasPreciseLocation() && !manualStartRequested) findManualRoutes();
            else if (code == PRECISE ? hasPreciseLocation() : hasNotifications()) activateManual();
            else notifyUser(code == PRECISE ? "Permite la ubicación precisa para calcular la ruta." : "Permite las notificaciones para recibir avisos.");
        } else if (code == BACKGROUND || code == AUTO_PRECISE || code == BACKGROUND + 10) {
            CommuteScheduler.refresh(this);
            render();
        }
    }

    private void openPreference(PreferenceScreen screen) {
        preferenceScreen = screen;
        render();
    }

    private void openAudio(boolean forDraft) {
        audioForDraft = forDraft;
        secretTaps = 0;
        openPreference(PreferenceScreen.ALARM_AUDIO);
    }

    private void preferencesPage(LinearLayout page) {
        TextView heading = subpageHeader(page, preferenceScreen.title);
        switch (preferenceScreen) {
            case SETTINGS -> {
                LinearLayout links = preferenceCard(page);
                preferenceRow(links, R.drawable.settings_music, "Audio y vibración", "Tono y prueba de sonido", "›",
                        () -> openPreference(PreferenceScreen.SOUND));
                preferenceRow(links, R.drawable.settings_bell, "Antelación del aviso", "Viajes puntuales y transbordos", "›",
                        () -> openPreference(PreferenceScreen.ADVANCE));
                preferenceRow(links, R.drawable.settings_progress, "Línea de progreso",
                        preferences.getBoolean("verticalProgress", false) ? "Vertical" : "Horizontal", "›",
                        () -> openPreference(PreferenceScreen.PROGRESS));
                preferenceRow(links, R.drawable.settings_privacy, "Datos y privacidad", "Ubicación y fuentes de transporte", "›",
                        () -> openPreference(PreferenceScreen.PRIVACY));
            }
            case SOUND -> {
                LinearLayout sound = preferenceCard(page);
                preferenceRow(sound, R.drawable.settings_music, AlarmAudio.name(this, audioId(false)),
                        "Cambiar el audio o escuchar una prueba", "›", () -> openAudio(false));
                addPreferenceDivider(sound);
                Switch enabled = toggle(sound, "Vibración", preferences.getBoolean("vibration", true));
                enabled.setTextSize(15);
                enabled.setOnCheckedChangeListener((view, on) -> preferences.edit().putBoolean("vibration", on).apply());
                LinearLayout outputs = preferenceCard(page);
                outputToggle(outputs, "Sonido por altavoz", AlarmPlayer.SPEAKER_ENABLED);
                addPreferenceDivider(outputs);
                outputToggle(outputs, "Sonido por auriculares", AlarmPlayer.HEADPHONES_ENABLED);
                LinearLayout auto = card(page, GREEN);
                LinearLayout note = row(auto);
                preferenceIcon(note, R.drawable.settings_info, false);
                LinearLayout copy = column();
                note.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
                title(copy, "Salida automática", 15).setTextColor(MINT);
                text(copy, "Con auriculares conectados, el aviso usa esa salida. Si se desconectan, usa el altavoz según tu ajuste.", 13, MUTED);
            }
            case ALARM_AUDIO -> {
                heading.setOnClickListener(view -> secretTap());
                alarmAudioPage(page);
            }
            case ADVANCE -> {
                title(page, "Viajes puntuales y transbordos", 16);
                preferenceChoices(page, new String[]{"Al llegar", "1 parada antes", "2 paradas antes", "3 paradas antes"},
                        SessionStore.configuredAdvance(this), choice -> preferences.edit().putInt(SessionStore.ADVANCE_PREFERENCE, choice).apply());
            }
            case PROGRESS -> preferenceChoices(page, new String[]{"Horizontal", "Vertical"},
                    preferences.getBoolean("verticalProgress", false) ? 1 : 0,
                    choice -> preferences.edit().putBoolean("verticalProgress", choice == 1).apply());
            case PRIVACY -> {
                LinearLayout location = preferenceCard(page);
                title(location, "Tu ubicación se procesa en el móvil", 17);
                text(location, "SlipTrain calcula tus rutas en el dispositivo. Las consultas de Renfe no incluyen tus coordenadas.", 14, MUTED);
                LinearLayout sources = preferenceCard(page);
                caption(sources, "FUENTES");
                text(sources, "Origen de los datos: Renfe Operadora", 15, TEXT);
                text(sources, "CC BY 4.0 · Datos transformados", 13, MUTED);
                preferenceRow(sources, 0, "Powered by CRTM", "Datos transformados para calcular recorridos", "›",
                        () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.crtm.es/"))));
            }
        }
    }

    private void outputToggle(LinearLayout card, String label, String key) {
        Switch output = toggle(card, label, preferences.getBoolean(key, true));
        output.setTextSize(15);
        output.setOnCheckedChangeListener((view, on) -> preferences.edit().putBoolean(key, on).apply());
    }

    private void preferenceChoices(LinearLayout page, String[] labels, int current, Consumer<Integer> selected) {
        LinearLayout choices = preferenceCard(page);
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            PreferenceRow option = preferenceRow(choices, 0, labels[i], "", current == i ? "✓" : "", () -> {
                selected.accept(index);
                render();
            });
            option.row.setSelected(current == i);
        }
    }

    private void alarmAudioPage(LinearLayout page) {
        String id = audioId(audioForDraft);
        LinearLayout current = row(preferenceCard(page));
        current.setMinimumHeight(dp(58));
        preferenceIcon(current, R.drawable.settings_music, true);
        LinearLayout name = column();
        current.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        boolean imported = !id.isEmpty() && !AlarmAudio.isBundledSecret(id);
        text(name, imported ? "Archivo seleccionado" : "Tono actual", 13, MUTED).setPadding(0, 0, 0, 0);
        title(name, AlarmAudio.name(this, id), 16).setPadding(0, dp(2), 0, 0);

        LinearLayout actions = preferenceCard(page);
        preferenceRow(actions, R.drawable.settings_file, "Elegir archivo", "Abre el selector de Android", "›", this::pickAudio);
        PreferenceRow example = preferenceRow(actions, R.drawable.settings_play, getString(R.string.start_sound_preview),
                "Duración · 5 segundos", "›", () -> {
                    if (preview != null) stopPreview();
                    else previewAudio(audioId(audioForDraft));
                });
        previewLabel = example.primary;
        previewDetail = example.secondary;
        previewIcon = example.icon;
        updatePreviewRow();
        preferenceRow(actions, R.drawable.settings_bell, "Usar tono de SlipTrain", "", id.isEmpty() ? "✓" : "",
                () -> setAudio(audioForDraft, ""));
        String secret = AlarmAudio.secretTone(this);
        if (!secret.isEmpty()) {
            preferenceRow(actions, R.drawable.settings_music, AlarmAudio.SECRET_NAME,
                    "Play Him Off, Keyboard Cat · Charlie Schmidt", id.equals(secret) ? "✓" : "",
                    () -> setAudio(audioForDraft, secret));
        }
        String oldSecret = AlarmAudio.importedSecretTone(this);
        if (!oldSecret.isEmpty()) {
            preferenceRow(actions, R.drawable.settings_music, AlarmAudio.SECRET_IMPORT_NAME, "", id.equals(oldSecret) ? "✓" : "",
                    () -> setAudio(audioForDraft, oldSecret));
        }
    }

    private void secretTap() {
        long now = SystemClock.uptimeMillis();
        if (now - lastSecretTap > 800) secretTaps = 0;
        lastSecretTap = now;
        if (++secretTaps < 7) return;
        secretTaps = 0;
        String secret = AlarmAudio.unlockSecretTone(this);
        setAudio(audioForDraft, secret);
        previewAudio(secret);
    }

    private void pickAudio() {
        stopPreview();
        try { startActivityForResult(AlarmAudio.pickerIntent(), AUDIO); }
        catch (RuntimeException missing) { notifyUser("No se pudo abrir el selector de archivos."); }
    }

    private LinearLayout preferenceCard(LinearLayout page) {
        LinearLayout group = card(page, CARD);
        group.setBackground(outlined(CARD, EDGE, 22));
        group.setPadding(dp(16), dp(6), dp(16), dp(6));
        return group;
    }

    private void addPreferenceDivider(LinearLayout group) {
        View divider = new View(this);
        divider.setBackgroundColor(EDGE);
        divider.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        group.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private PreferenceRow preferenceRow(LinearLayout group, int icon, String label, String detail, String trailing, Runnable action) {
        if (group.getChildCount() > 0) addPreferenceDivider(group);
        LinearLayout line = row(group);
        line.setMinimumHeight(dp(detail.isEmpty() ? 53 : 64));
        line.setPadding(0, dp(8), 0, dp(8));
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3387bf9c), null,
                background(android.graphics.Color.WHITE, 12)));
        ImageView graphic = icon == 0 ? null : preferenceIcon(line, icon, true);
        LinearLayout labels = column();
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView primary = title(labels, label, 15);
        primary.setPadding(0, 0, 0, 0);
        TextView secondary = null;
        if (!detail.isEmpty()) {
            secondary = text(labels, detail, 13, MUTED);
            secondary.setPadding(0, dp(2), 0, 0);
        }
        if (!trailing.isEmpty()) {
            TextView indicator = text(line, trailing, 18, trailing.equals("✓") ? MINT : MUTED);
            indicator.setPadding(dp(10), 0, dp(2), 0);
            indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        if (Build.VERSION.SDK_INT >= 28) line.setScreenReaderFocusable(true);
        line.setFocusable(true);
        line.setOnClickListener(view -> action.run());
        return new PreferenceRow(line, primary, secondary, graphic);
    }

    private ImageView preferenceIcon(LinearLayout line, int icon, boolean circular) {
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (circular) {
            image.setBackground(background(GREEN, 18));
            image.setPadding(dp(8), dp(8), dp(8), dp(8));
        }
        int size = dp(circular ? 36 : 18);
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(size, size);
        box.setMarginEnd(dp(12));
        line.addView(image, box);
        return image;
    }

    private record PreferenceRow(LinearLayout row, TextView primary, TextView secondary, ImageView icon) { }

    private String audioId(boolean forDraft) {
        return forDraft ? draft.audioId : preferences.getString("audioId", "");
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != AUDIO || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        boolean forDraft = audioForDraft;
        Draft recipient = draft;
        worker.execute(() -> {
            try {
                AlarmAudio.Choice choice = AlarmAudio.importAudio(this, uri);
                main.post(() -> {
                    if (!isDestroyed() && (!forDraft || recipient == draft)) setAudio(forDraft, choice.id);
                });
            } catch (Exception failure) {
                main.post(() -> { if (!isDestroyed()) notifyUser("No se pudo abrir ese audio. Elige otro archivo."); });
            }
        });
    }

    private void setAudio(boolean forDraft, String id) {
        stopPreview();
        if (forDraft) {
            if (draft == null) return;
            draft.audioId = id;
        } else {
            preferences.edit().putString("audioId", id).apply();
        }
        if (preferenceScreen == PreferenceScreen.ALARM_AUDIO && audioForDraft == forDraft
                || preferenceScreen == PreferenceScreen.SOUND && !forDraft
                || forDraft && preferenceScreen == null && stationPicker == null && routeSelection == null) {
            render();
        }
    }

    private void previewAudio(String audioId) {
        boolean vibration = audioForDraft ? draft.vibration : preferences.getBoolean("vibration", true);
        previewStatus = "";
        preview = new AlarmPlayer(this, message -> {
            previewStatus = message;
            updatePreviewRow();
        });
        preview.start("auto", vibration, true, audioId);
        updatePreviewRow();
        main.postDelayed(stopPreviewTask, 5_000);
    }

    private void stopPreview() {
        main.removeCallbacks(stopPreviewTask);
        if (preview != null) { preview.stop(); preview = null; }
        updatePreviewRow();
    }

    private void updatePreviewRow() {
        if (previewLabel == null) return;
        boolean playing = preview != null;
        previewLabel.setText(playing ? R.string.stop_sound_preview : R.string.start_sound_preview);
        previewDetail.setText(!playing ? "Duración · 5 segundos"
                : previewStatus.isEmpty() ? "Reproduciendo · 5 segundos" : previewStatus);
        previewIcon.setImageResource(playing ? R.drawable.settings_stop : R.drawable.settings_play);
    }

    @Override protected void onStop() {
        stopPreview();
        super.onStop();
    }

    private String display(String id, String fallback) {
        Place place = planner == null || id == null || id.isEmpty() ? null : planner.place(id);
        return place == null ? fallback : place.name;
    }

    private String profileName(CommuteProfile profile) {
        return profile.label.isEmpty() ? display(profile.destinationId, "Viaje recurrente") : profile.label;
    }

    private static String formatTime(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private static String dayLabel(int mask) {
        if (mask == CommuteProfile.DAYS_WEEKDAYS) return "Lunes a viernes";
        if (mask == CommuteProfile.DAYS_MWF) return "Lunes, miércoles y viernes";
        if (mask == CommuteProfile.DAYS_WEEKEND) return "Sábado y domingo";
        if (mask == CommuteProfile.DAYS_EVERY_DAY) return "Todos los días";
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < 7; i++) if ((mask & 1 << i) != 0) selected.add(SHORT_DAYS[i]);
        return String.join(" · ", selected);
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private void syncDraft() {
        if (draft != null && labelEditor != null) draft.label = labelEditor.getText().toString();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        if (parent != null) parent.addView(layout, new LinearLayout.LayoutParams(-1, -2));
        return layout;
    }

    private LinearLayout card(LinearLayout parent, int color) {
        LinearLayout layout = column();
        layout.setPadding(dp(16), dp(14), dp(16), dp(14));
        layout.setBackground(background(color, 22));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(layout, params);
        return layout;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radius));
        shape.setColor(color);
        return shape;
    }

    private GradientDrawable outlined(int color, int stroke, int radius) {
        GradientDrawable shape = background(color, radius);
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private RippleDrawable selectable(boolean selected, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x3387bf9c),
                outlined(selected ? GREEN : CARD, selected ? MINT : EDGE, radius), null);
    }

    private LinearLayout prepareSubpage(LinearLayout page, String heading) {
        LinearLayout body = column();
        body.setPadding(dp(20), dp(10), dp(20), dp(14));
        body.setFocusableInTouchMode(true);
        body.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        page.addView(body, new LinearLayout.LayoutParams(-1, -1));
        subpageHeader(body, heading);
        body.requestFocus();
        return body;
    }

    private TextView subpageHeader(LinearLayout page, String heading) {
        LinearLayout bar = row(page);
        ImageButton back = new ImageButton(this);
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3387bf9c), background(SURFACE, 14), null));
        back.setImageResource(R.drawable.ic_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("Volver");
        back.setPadding(0, 0, 0, 0);
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        back.setOnClickListener(view -> closeSubpage());
        TextView name = title(bar, heading, 24);
        name.setPadding(dp(12), dp(4), 0, dp(4));
        fillRemainder(name);
        if (Build.VERSION.SDK_INT >= 28) name.setAccessibilityHeading(true);
        return name;
    }

    private View actionRow(LinearLayout parent, String symbol, String label, CharSequence value, Runnable action) {
        LinearLayout line = row(parent);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) line.getLayoutParams();
        params.topMargin = dp(7);
        line.setMinimumHeight(dp(56));
        line.setPadding(dp(12), dp(6), dp(11), dp(6));
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3387bf9c), background(SURFACE, 13), null));
        TextView icon = text(line, symbol, 20, MINT);
        icon.setPadding(0, 0, dp(10), 0);
        LinearLayout labels = column();
        line.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        text(labels, label, 12, MUTED).setPadding(0, 0, 0, 0);
        TextView summary = title(labels, "", 15);
        summary.setPadding(0, dp(3), 0, 0);
        summary.setText(value);
        text(line, "›", 21, MUTED).setPadding(dp(8), 0, 0, 0);
        line.setContentDescription(label + ", " + value);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        line.setFocusable(true);
        line.setOnClickListener(view -> action.run());
        return line;
    }

    private TextView text(LinearLayout parent, String value, int size, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(size);
        label.setPadding(0, dp(4), 0, dp(4));
        parent.addView(label, new LinearLayout.LayoutParams(parent.getOrientation() == LinearLayout.VERTICAL ? -1 : -2, -2));
        return label;
    }

    private TextView title(LinearLayout parent, String value, int size) {
        TextView label = text(parent, value, size, TEXT);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private void caption(LinearLayout parent, String value) {
        TextView label = text(parent, value, 12, MINT);
        label.setLetterSpacing(.1f);
    }

    private void fillRemainder(View view) {
        view.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
    }

    private Button button(LinearLayout parent, String label, boolean primary, View.OnClickListener action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(primary ? BG : TEXT);
        button.setMinHeight(dp(48));
        button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x3387bf9c),
                background(primary ? MINT : SURFACE, 14), null));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        boolean row = parent.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(row ? -2 : -1, -2);
        if (!row) params.topMargin = dp(8);
        parent.addView(button, params);
        button.setOnClickListener(action);
        return button;
    }

    private Switch toggle(LinearLayout parent, String label, boolean checked) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(TEXT);
        toggle.setTextSize(16);
        toggle.setMinimumHeight(dp(48));
        toggle.setChecked(checked);
        parent.addView(toggle, new LinearLayout.LayoutParams(parent.getOrientation() == LinearLayout.VERTICAL ? -1 : -2, -2));
        return toggle;
    }

    private void notifyUser(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    private void alert(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Cerrar", null).show();
    }

    private enum PreferenceScreen {
        SETTINGS("Ajustes"), SOUND("Audio y vibración"), ALARM_AUDIO("Audio de la alarma"),
        ADVANCE("Antelación del aviso"), PROGRESS("Línea de progreso"), PRIVACY("Datos y privacidad");

        final String title;
        PreferenceScreen(String title) { this.title = title; }
    }

    private static final class StationPicker {
        final boolean recurring;
        String origin, destination, query = "";
        boolean choosingOrigin, destinationChosen, focusSearch;

        StationPicker(boolean recurring, String origin, String destination) {
            this.recurring = recurring;
            this.origin = origin;
            this.destination = destination;
            choosingOrigin = recurring;
        }

        StationPicker(Bundle state) {
            this(state.getBoolean("recurring"), state.getString("origin", ""), state.getString("destination", ""));
            query = state.getString("query", "");
            choosingOrigin = state.getBoolean("choosingOrigin");
            destinationChosen = state.getBoolean("destinationChosen");
        }

        Bundle bundle() {
            Bundle state = new Bundle();
            state.putBoolean("recurring", recurring);
            state.putString("origin", origin);
            state.putString("destination", destination);
            state.putString("query", query);
            state.putBoolean("choosingOrigin", choosingOrigin);
            state.putBoolean("destinationChosen", destinationChosen);
            return state;
        }
    }

    private static final class RouteSelection {
        final String origin, destination;
        final int advance;
        final boolean manual, roundTrip;
        String outboundKey, returnKey, error = "";
        boolean returning, transferAlarm, checkingLive, outboundExpanded, returnExpanded, animateExpansion, startListAtTop;
        List<Option> outbound = new ArrayList<>(), inbound = new ArrayList<>();
        final Order[] orders = {Order.RECOMMENDED, Order.RECOMMENDED};
        final Operator[] operators = {Operator.ALL, Operator.ALL};
        Runnable redraw;

        RouteSelection(String origin, String destination, int advance, boolean manual, boolean roundTrip,
                       String outboundKey, String returnKey, boolean transferAlarm) {
            this.origin = origin;
            this.destination = destination;
            this.advance = advance;
            this.manual = manual;
            this.roundTrip = roundTrip;
            this.outboundKey = outboundKey;
            this.returnKey = returnKey;
            this.transferAlarm = transferAlarm;
        }

        RouteSelection(Bundle state) {
            this(state.getString("origin", ""), state.getString("destination", ""), state.getInt("advance", 1),
                    state.getBoolean("manual"), state.getBoolean("roundTrip"), state.getString("outboundKey", ""),
                    state.getString("returnKey", ""), state.getBoolean("transferAlarm", true));
            returning = state.getBoolean("returning");
            outboundExpanded = state.getBoolean("outboundExpanded");
            returnExpanded = state.getBoolean("returnExpanded");
            for (int i = 0; i < 2; i++) {
                orders[i] = Order.valueOf(state.getString("order" + i, Order.RECOMMENDED.name()));
                operators[i] = Operator.valueOf(state.getString("operator" + i, Operator.ALL.name()));
            }
        }

        List<Option> options(boolean returning) { return RouteEstimates.filter(returning ? inbound : outbound, operator(returning)); }
        Order order(boolean returning) { return orders[returning ? 1 : 0]; }
        Operator operator(boolean returning) { return operators[returning ? 1 : 0]; }

        List<Option> choices(boolean returning, boolean expanded) {
            return expanded ? RouteEstimates.ranked(options(returning), order(returning))
                    : RouteEstimates.choices(options(returning), returning ? returnKey : outboundKey, order(returning));
        }

        boolean expanded(boolean returning) { return order(returning) != Order.RECOMMENDED || (returning ? returnExpanded : outboundExpanded); }

        void setExpanded(boolean returning, boolean expanded) {
            if (returning) returnExpanded = expanded; else outboundExpanded = expanded;
        }

        Option selected(List<Option> choices, boolean returning) {
            String key = returning ? returnKey : outboundKey;
            for (Option option : choices) if (option.plan().routeKey().equals(key)) return option;
            return choices.isEmpty() ? null : choices.get(0);
        }

        Bundle bundle() {
            Bundle state = new Bundle();
            state.putString("origin", origin);
            state.putString("destination", destination);
            state.putInt("advance", advance);
            state.putBoolean("manual", manual);
            state.putBoolean("roundTrip", roundTrip);
            state.putString("outboundKey", outboundKey);
            state.putString("returnKey", returnKey);
            state.putBoolean("transferAlarm", transferAlarm);
            state.putBoolean("returning", returning);
            state.putBoolean("outboundExpanded", outboundExpanded);
            state.putBoolean("returnExpanded", returnExpanded);
            for (int i = 0; i < 2; i++) {
                state.putString("order" + i, orders[i].name());
                state.putString("operator" + i, operators[i].name());
            }
            return state;
        }
    }

    private static final class Draft {
        String id = "", originId = "", destinationId = "", label = "", audioId = "";
        String outboundRoute = "", returnRoute = "";
        int outboundMinute = 7 * 60, returnMinute = 14 * 60, days = CommuteProfile.DAYS_WEEKDAYS;
        boolean vibration = true, enabled = true, transferAlarm = true;
        Draft() { }
        Draft(CommuteProfile profile) {
            id = profile.id;
            originId = profile.originId;
            destinationId = profile.destinationId;
            label = profile.label;
            audioId = profile.audioId;
            outboundMinute = profile.outboundMinute;
            returnMinute = profile.returnMinute;
            days = profile.daysMask;
            vibration = profile.vibration;
            enabled = profile.enabled;
            transferAlarm = profile.transferAlarm;
            outboundRoute = profile.outboundRoute;
            returnRoute = profile.returnRoute;
        }
        Draft(Bundle state) {
            id = state.getString("id", "");
            originId = state.getString("originId", "");
            destinationId = state.getString("destinationId", "");
            label = state.getString("label", "");
            audioId = state.getString("audioId", "");
            outboundMinute = state.getInt("out", 7 * 60);
            returnMinute = state.getInt("back", 14 * 60);
            days = state.getInt("days", CommuteProfile.DAYS_WEEKDAYS);
            vibration = state.getBoolean("vibration", true);
            enabled = state.getBoolean("enabled", true);
            transferAlarm = state.getBoolean(TRANSFER_ALARM, true);
            outboundRoute = state.getString("outboundRoute", "");
            returnRoute = state.getString("returnRoute", "");
        }
        Bundle bundle() {
            Bundle state = new Bundle();
            state.putString("id", id);
            state.putString("originId", originId);
            state.putString("destinationId", destinationId);
            state.putString("label", label);
            state.putString("audioId", audioId);
            state.putInt("out", outboundMinute);
            state.putInt("back", returnMinute);
            state.putInt("days", days);
            state.putBoolean("vibration", vibration);
            state.putBoolean("enabled", enabled);
            state.putBoolean(TRANSFER_ALARM, transferAlarm);
            state.putString("outboundRoute", outboundRoute);
            state.putString("returnRoute", returnRoute);
            return state;
        }
    }
}
