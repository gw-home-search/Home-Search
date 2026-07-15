package com.home.infrastructure.web.place;

import com.home.application.place.NearbyPlaceBounds;
import com.home.application.place.NearbyPlaceItem;
import com.home.application.place.ViewportNearbyPlacesResult;
import java.time.Instant;
import java.util.List;

public record ViewportNearbyPlacesResponse(
        Bounds bounds, int level, Source source, Instant generatedAt, Category category) {

    public static ViewportNearbyPlacesResponse from(ViewportNearbyPlacesResult result) {
        return new ViewportNearbyPlacesResponse(
                Bounds.from(result.bounds()),
                result.level(),
                new Source("KAKAO_LOCAL", "PROVIDER_SEARCH"),
                result.generatedAt(),
                new Category(
                        result.category().category().name(),
                        result.category().category().titleKo(),
                        result.category().retrievedAt(),
                        result.category().places().stream().map(Place::from).toList()));
    }

    public record Bounds(double swLat, double swLng, double neLat, double neLng) {
        private static Bounds from(NearbyPlaceBounds bounds) {
            return new Bounds(bounds.swLat(), bounds.swLng(), bounds.neLat(), bounds.neLng());
        }
    }

    public record Source(String provider, String countBasis) {}

    public record Category(String category, String label, Instant retrievedAt, List<Place> places) {}

    public record Place(
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
        private static Place from(NearbyPlaceItem item) {
            return new Place(
                    item.placeId(),
                    item.name(),
                    item.categoryDetail(),
                    item.lat(),
                    item.lng(),
                    item.distanceMeters(),
                    item.address(),
                    item.roadAddress(),
                    item.phone(),
                    item.placeUrl());
        }
    }
}
