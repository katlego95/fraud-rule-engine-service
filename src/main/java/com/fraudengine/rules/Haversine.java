package com.fraudengine.rules;

/** Great-circle distance. Accurate enough for "could a person have travelled this far". */
final class Haversine {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private Haversine() {}

    static double distanceKm(double latitude1, double longitude1, double latitude2, double longitude2) {
        double deltaLatitude = Math.toRadians(latitude2 - latitude1);
        double deltaLongitude = Math.toRadians(longitude2 - longitude1);

        double a = Math.pow(Math.sin(deltaLatitude / 2), 2)
                + Math.cos(Math.toRadians(latitude1)) * Math.cos(Math.toRadians(latitude2))
                * Math.pow(Math.sin(deltaLongitude / 2), 2);

        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
