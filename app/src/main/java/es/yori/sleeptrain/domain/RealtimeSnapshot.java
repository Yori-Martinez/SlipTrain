package es.yori.sleeptrain.domain;

import java.util.*;

public final class RealtimeSnapshot {
    public String stopId = "", vehicleStatus = "", critical = "", context = "";
    public long measuredAt, predictionAt, predictionExpiresAt;
    public Integer delaySeconds;
    public final List<String> alerts = new ArrayList<>();
    public boolean hasFreshVehicle, automaticAssociation;
    public TransitLine.Departure associatedTrip;
    public long associationGpsElapsed = -1;
    public double associationPosition;
}
