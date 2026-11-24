package es.yori.sleeptrain.domain;

import java.util.*;

/** Only the two documented L3/C5 interchanges are edges; names/proximity are not ID joins. */
public final class JourneyPlanner {
    public static final String EMB = "Embajadores", VILLAVERDE = "Villaverde Alto";
    private final Map<String, TransitLine> lines;
    public JourneyPlanner(Map<String, TransitLine> lines) { this.lines = lines; }
    public JourneyPlan direct(String lineId, String origin, String destination, int advance) {
        if (origin.equals(destination)) throw new IllegalArgumentException("Elige estaciones diferentes");
        return new JourneyPlan(Collections.singletonList(new JourneyPlan.Leg(line(lineId), origin, destination, advance)), "", 0);
    }
    public JourneyPlan transfer(String firstLine, String origin, String destination, int advance, String via) {
        TransitLine first = line(firstLine), second = line(TransitLine.METRO.equals(firstLine) ? TransitLine.RENFE : TransitLine.METRO);
        if ((TransitLine.METRO.equals(first.id) && ((origin.equals("par_4_46") && destination.equals("35609")) || (origin.equals("par_4_273") && destination.equals("37001"))))
                || (TransitLine.RENFE.equals(first.id) && ((origin.equals("35609") && destination.equals("par_4_46")) || (origin.equals("37001") && destination.equals("par_4_273"))))) {
            throw new IllegalArgumentException("Ya estás en el intercambiador; solo necesitas cambiar de andén");
        }
        JourneyPlan best = null;
        for (String name : Arrays.asList(EMB, VILLAVERDE)) {
            if (!via.isEmpty() && !name.equals(via)) continue;
            String metro = EMB.equals(name) ? "par_4_46" : "par_4_273";
            String renfe = EMB.equals(name) ? "35609" : "37001";
            String firstStop = TransitLine.METRO.equals(first.id) ? metro : renfe;
            String secondStop = TransitLine.METRO.equals(second.id) ? metro : renfe;
            List<JourneyPlan.Leg> legs = new ArrayList<>();
            if (!origin.equals(firstStop)) legs.add(new JourneyPlan.Leg(first, origin, firstStop, advance));
            if (!destination.equals(secondStop)) legs.add(new JourneyPlan.Leg(second, secondStop, destination, advance));
            if (legs.isEmpty()) continue;
            JourneyPlan candidate = new JourneyPlan(legs, name, 300);
            if (best == null || candidate.nominalSeconds() < best.nominalSeconds()) best = candidate;
        }
        if (best == null) throw new IllegalArgumentException("Ya estás en el intercambiador o no hay combinación");
        return best;
    }
    private TransitLine line(String id) {
        TransitLine result = lines.get(id);
        if (result == null) throw new IllegalArgumentException("Línea no disponible");
        return result;
    }
}
