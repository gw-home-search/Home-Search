package com.home.application.place;

public record NearbyPlaceItem(
        String placeId,
        String name,
        String categoryDetail,
        double lat,
        double lng,
        int distanceMeters,
        String address,
        String roadAddress,
        String phone,
        String placeUrl) {

    public NearbyPlaceItem withDistanceMeters(int newDistanceMeters) {
        return new NearbyPlaceItem(
                placeId, name, categoryDetail, lat, lng, newDistanceMeters, address, roadAddress, phone, placeUrl);
    }
}
