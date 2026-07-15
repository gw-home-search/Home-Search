package com.home.application.place;

import com.home.domain.place.NearbyPlaceCategory;
import java.time.Instant;
import java.util.List;

public record ViewportNearbyPlaceCategoryResult(
        NearbyPlaceCategory category, Instant retrievedAt, List<NearbyPlaceItem> places) {}
