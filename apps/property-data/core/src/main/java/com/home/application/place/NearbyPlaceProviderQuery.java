package com.home.application.place;

import com.home.domain.place.NearbyPlaceCategory;
import java.util.Objects;

public record NearbyPlaceProviderQuery(NearbyPlaceSearchArea area, NearbyPlaceCategory category) {

    public NearbyPlaceProviderQuery {
        Objects.requireNonNull(area);
        Objects.requireNonNull(category);
    }
}
