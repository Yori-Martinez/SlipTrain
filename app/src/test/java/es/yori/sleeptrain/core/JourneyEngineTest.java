package es.yori.sleeptrain.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class JourneyEngineTest {
    private final List<Station> stops = Arrays.asList(
            new Station("A", "Origen", 40.4, -3.6), new Station("B", "Intermedia", 40.39, -3.65),
            new Station("C", "Anterior al destino", 40.38, -3.7), new Station("D", "Destino", 40.37, -3.75));
    private JourneyEngine engine(int advance) { return new JourneyEngine(stops, new int[]{120, 240, 120}, advance, 100_000, 0, 100_000); }

    @Test public void defaultAlertIsPreviousStation() { assertEquals("C", engine(1).alertStation().id); }
    @Test public void arrivalCanBeSelected() { assertEquals("D", engine(0).alertStation().id); }
    @Test public void offlineBackupFiresBeforeNominalArrival() {
        JourneyEngine e = engine(1);
        assertEquals(445_000, e.deadline());
        assertFalse(e.shouldAlert(444_999)); assertTrue(e.shouldAlert(445_000));
    }
    @Test public void stationConfirmationAdjustsBackupAfterDelay() {
        JourneyEngine e = engine(1); e.confirmStation(1, 280_000);
        assertEquals(505_000, e.deadline());
    }
    @Test public void repeatedStationCannotPostponeAlarm() {
        JourneyEngine e = engine(1); e.confirmStation(1, 280_000); e.confirmStation(1, 350_000);
        assertEquals(505_000, e.deadline()); assertEquals(280_000, e.anchorAt());
    }
    @Test public void backwardObservationDoesNotResetProgress() {
        JourneyEngine e = engine(1); e.confirmStation(1, 280_000); e.confirmStation(0, 300_000);
        assertEquals(1, e.confirmedIndex()); assertEquals(505_000, e.deadline());
    }
    @Test public void freshTrainApproachingAlertStationTriggersImmediately() {
        JourneyEngine e = engine(1); e.observeRemote("C", "INCOMING_AT", 1_000_000, 1_020_000, 120_000);
        assertTrue(e.shouldAlert(120_000));
    }
    @Test public void inTransitToStationDoesNotMeanArrival() {
        JourneyEngine e = engine(1); e.observeRemote("C", "IN_TRANSIT_TO", 1_000_000, 1_020_000, 120_000);
        assertFalse(e.shouldAlert(120_000)); assertEquals(0, e.confirmedIndex());
    }
    @Test public void staleOrFutureFeedCannotAdvanceProgress() {
        JourneyEngine e = engine(1);
        e.observeRemote("D", "STOPPED_AT", 1_000_000, 1_061_000, 120_000);
        e.observeRemote("D", "STOPPED_AT", 1_100_000, 1_020_000, 120_000);
        assertEquals(0, e.confirmedIndex()); assertFalse(e.shouldAlert(120_000));
    }
    @Test public void unknownStopCannotAdvanceProgress() {
        JourneyEngine e = engine(1); e.observeRemote("OTHER", "STOPPED_AT", 1_000_000, 1_001_000, 120_000);
        assertEquals(0, e.confirmedIndex());
    }
    @Test public void clockChangesDoNotChangeElapsedDeadline() {
        JourneyEngine e = engine(1); long before = e.deadline();
        e.observeRemote("A", "STOPPED_AT", 86_505_000, 86_505_000, 120_000);
        assertEquals(before, e.deadline());
    }
    @Test public void restoredProgressKeepsSameDeadline() {
        JourneyEngine e = engine(1); e.confirmStation(1, 280_000);
        JourneyEngine restored = new JourneyEngine(stops, new int[]{120, 240, 120}, 1, 100_000, e.confirmedIndex(), e.anchorAt());
        assertEquals(e.deadline(), restored.deadline());
    }
    @Test public void jumpingPastAlertStationStillAlerts() {
        JourneyEngine e = engine(1); e.confirmStation(3, 120_000); assertTrue(e.shouldAlert(120_000));
    }
    @Test public void freshRealtimePredictionTakesPriorityButExpiresLocally() {
        JourneyEngine e = engine(1); e.prediction(600_000, 500_000);
        assertFalse(e.shouldAlert(460_000)); assertEquals(600_000, e.deadline(460_000));
        assertTrue(e.shouldAlert(500_001)); assertEquals(445_000, e.deadline(500_001));
    }
    @Test public void confirmedArrivalOverridesALatePrediction() {
        JourneyEngine e = engine(1); e.prediction(600_000, 350_000); e.confirmStation(2, 200_000);
        assertTrue(e.shouldAlert(200_000));
    }
    @Test public void aBriefGpsWindowCorrectsAnAdvancedEstimateBackwards() {
        JourneyEngine e = engine(1); e.confirmStation(1, 280_000); e.prediction(300_000, 350_000);
        assertTrue(e.correctPosition(0, 0, 290_000, 290_000));
        assertEquals(0, e.confirmedIndex()); assertEquals(635_000, e.deadline(290_000));
        assertFalse(e.shouldAlert(350_000)); assertEquals(0, e.predictionUntil());
    }
    @Test public void originDwellAndNewSameStationFixesReanchorButCachedFixesDoNot() {
        JourneyEngine e = engine(1);
        assertTrue(e.correctPosition(0, 0, 200_000, 200_000));
        assertTrue(e.correctPosition(0, 0, 250_000, 250_000));
        assertFalse(e.correctPosition(0, 0, 250_000, 260_000));
        assertEquals(250_000, e.anchorAt()); assertEquals(595_000, e.deadline());
    }
    @Test public void preciseDevicePositionOverridesContradictingRenfeArrivalAndPrediction() {
        JourneyEngine e = engine(1); e.correctPosition(1, 1, 300_000, 300_000);
        assertFalse(e.observeRemote("C", "STOPPED_AT", 1_005_000, 1_010_000, 310_000));
        e.prediction(310_000, 360_000);
        assertFalse(e.shouldAlert(310_000)); assertEquals(1, e.confirmedIndex());
        assertEquals(525_000, e.deadline(310_000));
    }
    @Test public void oldRemoteObservationCannotOverwriteGpsAfterPriorityWindowExpires() {
        JourneyEngine e = engine(1); e.correctPosition(0, 0, 300_000, 300_000);
        assertFalse(e.observeRemote("C", "STOPPED_AT", 1_000_000, 1_055_000, 340_000));
        assertEquals(0, e.confirmedIndex());
        assertTrue(e.observeRemote("B", "STOPPED_AT", 1_054_000, 1_055_000, 340_000));
        assertEquals(1, e.confirmedIndex());
    }
    @Test public void gpsBetweenStationsUpdatesRemainingTimeWithoutPretendingArrival() {
        JourneyEngine e = engine(1); e.correctPosition(1.5, -1, 300_000, 300_000);
        assertEquals(1, e.confirmedIndex()); assertEquals(405_000, e.deadline());
        assertFalse(e.shouldAlert(320_000));
    }
    @Test public void freshGpsBeforeAlertPreventsTimerFiringUntilSignalExpires() {
        JourneyEngine e = engine(1); e.correctPosition(1.99, -1, 300_000, 300_000);
        assertFalse(e.shouldAlert(310_000)); assertFalse(e.shouldAlert(330_000));
        assertTrue(e.shouldAlert(330_001));
    }
    @Test public void gpsAtAlertFiresImmediatelyEvenWithLateOperatorPrediction() {
        JourneyEngine e = engine(1); e.prediction(900_000, 800_000);
        e.correctPosition(2, 2, 300_000, 300_000); assertTrue(e.shouldAlert(300_000));
    }
    @Test public void signalLossUsesDownloadedDurationsFromLastFix() {
        JourneyEngine e = engine(1); e.correctPosition(1, 1, 300_000, 300_000);
        assertEquals(525_000, e.deadline(400_000)); assertTrue(e.shouldAlert(525_000));
    }
    @Test public void observedStationIntervalsCalibratePaceWithoutRepeatedDwellBias() {
        JourneyEngine e = engine(0); e.correctPosition(0, 0, 100_000, 100_000);
        e.correctPosition(0, 0, 120_000, 120_000);
        e.correctPosition(1, 1, 280_000, 280_000);
        assertEquals(1.25, e.pace(), .001);
        assertEquals(715_000, e.deadline());
    }
    @Test public void invalidOrPreBoardingDeviceFixDoesNotChangeDeadline() {
        JourneyEngine e = engine(1); long before = e.deadline();
        assertFalse(e.correctPosition(1, 1, 99_000, 100_000));
        assertFalse(e.correctPosition(Double.NaN, -1, 110_000, 110_000));
        assertFalse(e.correctPosition(1, 1, 110_000, 130_000));
        assertEquals(before, e.deadline());
    }
    @Test public void criticalOperatorIncidentDoesNotReplaceAvailableGpsWithEarlyAlarm() {
        JourneyEngine e = engine(1); e.correctPosition(0, 0, 200_000, 200_000);
        assertFalse(e.shouldAlertForIncident("Tren cancelado", 210_000)); assertFalse(e.shouldAlert(210_000));
        assertTrue(e.shouldAlertForIncident("Tren cancelado", 230_001));
        assertFalse(e.shouldAlertForIncident("", 230_001));
        e.correctPosition(2, 2, 240_000, 240_000); assertTrue(e.shouldAlert(240_000));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsAdvanceBeyondOrigin() { engine(4); }
    @Test(expected = IllegalArgumentException.class) public void rejectsZeroDuration() { new JourneyEngine(stops, new int[]{0, 1, 1}, 1, 0, 0, 0); }
}

