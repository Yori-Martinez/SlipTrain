package es.yori.sleeptrain.presentation;

import android.content.Context;
import android.os.*;
import es.yori.sleeptrain.data.TransitRepository;
import es.yori.sleeptrain.domain.*;
import java.util.*;
import java.util.concurrent.*;

/** Presentation coordinator: the Activity renders; repositories perform I/O; domain plans routes. */
public final class JourneyPresenter {
    public interface Listener { void loaded(Map<String, TransitLine> lines, String message); void failed(String message); }
    private final TransitRepository repository;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private Map<String, TransitLine> lines = Collections.emptyMap();
    public JourneyPresenter(Context context) { repository = new TransitRepository(context); }
    public void load(Listener listener) {
        worker.execute(() -> {
            try { Map<String, TransitLine> loaded = repository.load(); main.post(() -> { if (!closed) { lines = loaded; listener.loaded(lines, "Catálogos locales cargados"); } }); }
            catch (Exception error) { main.post(() -> { if (!closed) listener.failed("No se pudieron leer los recorridos"); }); }
        });
    }
    public void refresh(Listener listener) {
        final Map<String, TransitLine> current = lines;
        worker.execute(() -> {
            Map<String, TransitLine> result = new LinkedHashMap<>(current); List<String> messages = new ArrayList<>();
            for (TransitLine line : current.values()) {
                try { result.put(line.id, repository.refresh(line)); messages.add(line.name + ": descargado"); }
                catch (Exception error) { messages.add(line.name + ": se conserva el catálogo anterior"); }
            }
            main.post(() -> { if (!closed) { lines = Collections.unmodifiableMap(result); listener.loaded(lines, String.join("\n", messages)); } });
        });
    }
    public JourneyPlan plan(boolean transfer, String firstLine, String origin, String destination, int advance, String via) {
        JourneyPlanner planner = new JourneyPlanner(lines);
        return transfer ? planner.transfer(firstLine, origin, destination, advance, via) : planner.direct(firstLine, origin, destination, advance);
    }
    public void close() { closed = true; worker.shutdownNow(); main.removeCallbacksAndMessages(null); }
}
