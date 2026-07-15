package com.home.application.place;

public record NearbyPlaceBounds(double swLat, double swLng, double neLat, double neLng) {

    public boolean contains(double lat, double lng) {
        return lat >= swLat && lat <= neLat && lng >= swLng && lng <= neLng;
    }

    public NearbyPlacePoint center() {
        return new NearbyPlacePoint((swLat + neLat) / 2, (swLng + neLng) / 2);
    }
}
