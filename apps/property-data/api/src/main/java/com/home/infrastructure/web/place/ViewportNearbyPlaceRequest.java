package com.home.infrastructure.web.place;

import com.home.domain.place.NearbyPlaceCategory;
import jakarta.validation.constraints.NotNull;

public record ViewportNearbyPlaceRequest(
        @NotNull Double swLat,
        @NotNull Double swLng,
        @NotNull Double neLat,
        @NotNull Double neLng,
        @NotNull Integer level,
        @NotNull NearbyPlaceCategory category) {}
