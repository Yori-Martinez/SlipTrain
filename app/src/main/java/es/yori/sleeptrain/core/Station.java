package es.yori.sleeptrain.core;

public final class Station {
    public final String id;
    public final String name;
    public final double latitude;
    public final double longitude;
    public final String placeId;
    public final String placeName;

    public Station(String id, String name, double latitude, double longitude) {
        this(id, name, latitude, longitude, id, name);
    }

    public Station(String id, String name, double latitude, double longitude, String placeId, String placeName) {
        this.id = id;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.placeId = placeId;
        this.placeName = placeName;
    }
}

