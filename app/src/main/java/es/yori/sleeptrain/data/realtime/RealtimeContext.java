package es.yori.sleeptrain.data.realtime;

import es.yori.sleeptrain.domain.TransitLine;
import java.util.*;

public final class RealtimeContext {
    public final TransitLine.ScheduledTrip trip;
    public final String serviceDate, alertStop, destination;
    public final Set<String> routeIds, agencies, stops;
    public RealtimeContext(TransitLine.ScheduledTrip trip, String serviceDate, String alertStop, String destination,
                           Set<String> routeIds, Set<String> agencies, Set<String> stops) {
        this.trip = trip; this.serviceDate = serviceDate; this.alertStop = alertStop; this.destination = destination;
        this.routeIds = routeIds; this.agencies = agencies; this.stops = stops;
    }
    public RealtimeContext withTrip(TransitLine.Departure departure) {
        return new RealtimeContext(departure == null ? null : departure.trip, departure == null ? "" : departure.date,
                alertStop, destination, routeIds, agencies, stops);
    }
}

