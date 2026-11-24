package es.yori.sleeptrain.domain;

import org.junit.Test;
import static org.junit.Assert.*;

public class JourneyPlannerTest {
    private JourneyPlanner planner() throws Exception { return new JourneyPlanner(TransitLineTest.catalog()); }
    @Test public void habitualTripStillAlertsAtMostoles() throws Exception {
        JourneyPlan plan = planner().direct(TransitLine.RENFE, "18000", "35607", 1);
        assertEquals(1, plan.legs.size()); assertEquals("35606", plan.legs.get(0).alertStation().id);
    }
    @Test public void metroToC5UsesEmbajadoresAndTwoSeparateLegs() throws Exception {
        JourneyPlan plan = planner().transfer(TransitLine.METRO, "par_4_48", "35607", 1, "");
        assertEquals("Embajadores", plan.transferName); assertEquals(2, plan.legs.size());
        assertEquals("par_4_46", plan.legs.get(0).destination); assertEquals("35609", plan.legs.get(1).origin);
        assertEquals("par_4_47", plan.legs.get(0).alertStation().id); assertEquals("35606", plan.legs.get(1).alertStation().id);
    }
    @Test public void reverseTransferCanUseVillaverdeAlto() throws Exception {
        JourneyPlan plan = planner().transfer(TransitLine.RENFE, "35012", "par_4_228", 1, "Villaverde Alto");
        assertEquals("37001", plan.legs.get(0).destination); assertEquals("par_4_273", plan.legs.get(1).origin);
    }
    @Test public void alreadyAtTransferSkipsZeroLengthTrainLeg() throws Exception {
        JourneyPlan plan = planner().transfer(TransitLine.METRO, "par_4_46", "35607", 1, "Embajadores");
        assertEquals(1, plan.legs.size()); assertEquals(TransitLine.RENFE, plan.legs.get(0).line.id);
    }
    @Test public void shortLegDoesNotCountStopsOnOtherNetwork() throws Exception {
        JourneyPlan plan = planner().transfer(TransitLine.METRO, "par_4_47", "35607", 3, "Embajadores");
        assertEquals(1, plan.legs.get(0).advance); assertEquals("par_4_47", plan.legs.get(0).alertStation().id);
    }
    @Test(expected = IllegalArgumentException.class) public void sameInterchangeDoesNotGenerateALongDetour() throws Exception { planner().transfer(TransitLine.METRO, "par_4_46", "35609", 1, ""); }
    @Test(expected = IllegalArgumentException.class) public void directTripNeedsMovement() throws Exception { planner().direct(TransitLine.METRO, "par_4_46", "par_4_46", 1); }
}
