package com.home.infrastructure.web.place;

import com.home.application.place.NearbyPlaceCategoryResult;
import com.home.application.place.NearbyPlaceItem;
import com.home.application.place.NearbyPlacesResult;
import com.home.domain.place.NearbyPlaceCategory;
import java.time.Instant;
import java.util.List;

public record NearbyPlacesResponse(
        Long complexId,
        CenterResponse center,
        int radiusMeters,
        SourceResponse source,
        Instant generatedAt,
        List<CategoryResponse> categories) {

    public static NearbyPlacesResponse from(NearbyPlacesResult result) {
        return new NearbyPlacesResponse(
                result.complexId(),
                new CenterResponse(result.center().lat(), result.center().lng()),
                result.radiusMeters(),
                new SourceResponse("KAKAO_LOCAL", "PROVIDER_SEARCH"),
                result.generatedAt(),
                result.categories().stream().map(CategoryResponse::from).toList());
    }

    public record CenterResponse(double lat, double lng) {}

    public record SourceResponse(String provider, String countBasis) {}

    public record CategoryResponse(
            NearbyPlaceCategory category,
            String label,
            int matchedCount,
            int returnedCount,
            boolean hasMore,
            Instant retrievedAt,
            List<PlaceResponse> places) {

        private static CategoryResponse from(NearbyPlaceCategoryResult result) {
            return new CategoryResponse(
                    result.category(),
                    result.category().titleKo(),
                    result.matchedCount(),
                    result.returnedCount(),
                    result.hasMore(),
                    result.retrievedAt(),
                    result.places().stream().map(PlaceResponse::from).toList());
        }
    }

    public record PlaceResponse(
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

        private static PlaceResponse from(NearbyPlaceItem item) {
            return new PlaceResponse(
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
