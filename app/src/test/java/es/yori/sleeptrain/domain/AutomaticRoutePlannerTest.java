package es.yori.sleeptrain.domain;

import es.yori.sleeptrain.core.Station;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AutomaticRoutePlannerTest {
    private static AutomaticRoutePlanner planner;

    @BeforeClass public static void loadNetwork() throws Exception {
        planner = new AutomaticRoutePlanner(TransitLineTest.catalog());
    }

    @Test public void gpsChoosesAlfonsoXiiiAndRoutesToTheMostolesInterchange() {
        AutomaticRoutePlanner.Place mostoles = planner.place("35606");
        assertSame(mostoles, planner.place("par_4_214"));
        assertEquals("Móstoles Central", mostoles.name);
        assertEquals("Alfonso XIII", planner.nearest(40.44825, -3.66791).name);
        JourneyPlan plan = planner.plan(40.44825, -3.66791, mostoles.id, 1);
        assertEquals("METRO_L4", plan.legs.get(0).line.id);
        assertEquals("par_4_66", plan.legs.get(0).origin);
        JourneyPlan.Leg last = plan.legs.get(plan.legs.size() - 1);
        assertSame(mostoles, planner.place(last.destination));
        assertEquals(last.stations.get(last.stations.size() - 2).id, last.alertStation().id);
        assertTrue(plan.nominalSeconds() < 75 * 60);
    }

    @Test public void savedTripReversesAndUsesTheDestinationStationGroup() {
        JourneyPlan plan = planner.plan("rail-35606", "par_4_66", 1);
        assertSame(planner.place("rail-35606"), planner.place(plan.legs.get(0).origin));
        assertEquals("par_4_66", plan.legs.get(plan.legs.size() - 1).destination);
    }

    @Test public void madridAlternativesPreserveTheirOwnInterchangesWhenRestored() {
        List<JourneyPlan> choices = planner.alternatives("par_4_66", "rail-35606", 1);
        assertEquals(3, choices.size());
        assertEquals(planner.plan("par_4_66", "rail-35606", 1).routeKey(), choices.get(0).routeKey());
        Set<String> keys = new HashSet<>();
        int shortest = choices.get(0).nominalSeconds();
        for (JourneyPlan choice : choices) {
            assertTrue(keys.add(choice.routeKey()));
            assertTrue(choice.nominalSeconds() >= shortest);
            JourneyPlan restored = planner.restore(choice.routeKey(), "par_4_66", "rail-35606", 2);
            assertEquals(choice.routeKey(), restored.routeKey());
            assertEquals(choice.transferName, restored.transferName);
            assertEquals(choice.nominalSeconds(), restored.nominalSeconds());
            assertEquals(2, restored.legs.get(restored.legs.size() - 1).advance);
        }
    }

    @Test public void canConstrainReplanningToTheObservedLineAtAnInterchange() {
        AutomaticRoutePlanner.Place mostoles = planner.place("rail-35606");
        assertTrue(planner.serves(mostoles, "METRO_L12"));
        assertTrue(planner.serves(mostoles, TransitLine.RENFE));
        assertFalse(planner.serves(mostoles, "METRO_L4"));
        JourneyPlan viaMetro = planner.plan(mostoles.id, "par_4_66", 1, "METRO_L12");
        assertEquals("METRO_L12", viaMetro.legs.get(0).line.id);
        assertTrue(planner.departurePlans(mostoles.id, "par_4_66", 1).stream()
                .anyMatch(plan -> TransitLine.RENFE.equals(plan.legs.get(0).line.id)));
    }

    @Test public void mostolesToAlfonsoIncludesBothEmbajadoresItineraries() {
        List<JourneyPlan> plans = planner.routeCandidates("rail-35606", "par_4_66", 1);
        assertInterchanges(plans, Arrays.asList("RENFE_C5", "METRO_L3", "METRO_L4"),
                Arrays.asList("Embajadores", "Argüelles"));
        assertInterchanges(plans, Arrays.asList("RENFE_C5", "METRO_L3", "METRO_L2", "METRO_L4"),
                Arrays.asList("Embajadores", "Sol", "Goya"));
    }

    private void assertInterchanges(List<JourneyPlan> plans, List<String> lines, List<String> interchanges) {
        JourneyPlan plan = plans.stream().filter(itinerary -> itinerary.legs.stream().map(leg -> leg.line.id)
                .collect(java.util.stream.Collectors.toList()).equals(lines)).findFirst()
                .orElseThrow(() -> new AssertionError(lines));
        assertEquals(interchanges, plan.legs.stream().skip(1).map(leg -> planner.place(leg.origin).name)
                .collect(java.util.stream.Collectors.toList()));
        assertEquals(plan.routeKey(), planner.restore(plan.routeKey(), "rail-35606", "par_4_66", 1).routeKey());
    }

    @Test public void circularLineUsesTheShortLegAcrossItsStart() {
        TransitLine line = new TransitLine("METRO_CIRCULAR", "Circular", Arrays.asList(
                new Station("a", "A", 40, -4), new Station("b", "B", 40.1, -4),
                new Station("c", "C", 40.1, -3.9), new Station("d", "D", 40, -3.9)),
                new int[]{180, 180, 180}, Collections.emptyList(), Collections.emptySet(), Collections.emptySet(),
                "", "", "", "", 120);
        AutomaticRoutePlanner ring = new AutomaticRoutePlanner(Collections.singletonMap(line.id, line));
        JourneyPlan plan = ring.plan("a", "d", 0);
        assertEquals(1, plan.legs.size());
        assertEquals(120, plan.nominalSeconds());
        assertEquals("Sentido 2", plan.legs.get(0).direction);
        assertEquals(2, ring.departurePlans("a", "d", 0).size());
        assertEquals(2, ring.alternatives("a", "d", 0).size());
        assertEquals(120, ring.restore(plan.routeKey(), "a", "d", 0).nominalSeconds());
    }

    @Test public void distantAndImpreciseOriginsAreNotSnappedToTheNetwork() {
        assertNull(planner.nearest(41.5, -5));
        assertNull(planner.nearest(Double.NaN, -3.7));
        assertNull(planner.nearest(40.44825, -3.667, 10));
    }
}
