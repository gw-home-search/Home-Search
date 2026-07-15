package com.home.infrastructure.cache.place;

import com.home.application.place.NearbyPlacePoint;
import com.home.domain.place.NearbyPlaceCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record NearbyPlaceCacheKey(String lat, String lng, int radiusMeters, NearbyPlaceCategory category) {

    public static NearbyPlaceCacheKey from(NearbyPlacePoint center, int radiusMeters, NearbyPlaceCategory category) {
        return new NearbyPlaceCacheKey(coordinate(center.lat()), coordinate(center.lng()), radiusMeters, category);
    }

    public String redisKey() {
        return "home-search:nearby-place:kakao:format-1:" + lat + ":" + lng + ":" + radiusMeters + ":"
                + category.name();
    }

    private static String coordinate(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).toPlainString();
    }
}
