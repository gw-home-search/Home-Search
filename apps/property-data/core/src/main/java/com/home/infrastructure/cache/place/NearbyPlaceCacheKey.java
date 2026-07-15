package com.home.infrastructure.cache.place;

import com.home.application.place.NearbyPlaceBoundsArea;
import com.home.application.place.NearbyPlacePoint;
import com.home.application.place.NearbyPlaceProviderQuery;
import com.home.application.place.NearbyPlaceRadiusArea;
import com.home.domain.place.NearbyPlaceCategory;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record NearbyPlaceCacheKey(String redisKey, NearbyPlaceCategory category, Scope scope) {

    public static NearbyPlaceCacheKey from(NearbyPlacePoint center, int radiusMeters, NearbyPlaceCategory category) {
        return from(new NearbyPlaceProviderQuery(new NearbyPlaceRadiusArea(center, radiusMeters), category));
    }

    public static NearbyPlaceCacheKey from(NearbyPlaceProviderQuery query) {
        if (query.area() instanceof NearbyPlaceRadiusArea area) {
            String key = "home-search:nearby-place:kakao:format-1:"
                    + coordinate(area.center().lat(), 6) + ":"
                    + coordinate(area.center().lng(), 6) + ":"
                    + area.radiusMeters() + ":"
                    + query.category().name();
            return new NearbyPlaceCacheKey(key, query.category(), Scope.COMPLEX);
        }
        if (query.area() instanceof NearbyPlaceBoundsArea area) {
            String key = "home-search:nearby-place:kakao:viewport:format-1:"
                    + area.level() + ":"
                    + coordinate(area.bounds().swLat(), 3) + ":"
                    + coordinate(area.bounds().swLng(), 3) + ":"
                    + coordinate(area.bounds().neLat(), 3) + ":"
                    + coordinate(area.bounds().neLng(), 3) + ":"
                    + query.category().name();
            return new NearbyPlaceCacheKey(key, query.category(), Scope.VIEWPORT);
        }
        throw new IllegalArgumentException("unsupported nearby place search area");
    }

    private static String coordinate(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    public enum Scope {
        COMPLEX("complex"),
        VIEWPORT("viewport");

        private final String metricValue;

        Scope(String metricValue) {
            this.metricValue = metricValue;
        }

        public String metricValue() {
            return metricValue;
        }
    }
}
