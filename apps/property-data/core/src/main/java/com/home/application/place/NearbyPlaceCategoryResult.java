package com.home.application.place;

import com.home.domain.place.NearbyPlaceCategory;
import java.time.Instant;
import java.util.List;

public record NearbyPlaceCategoryResult(
        NearbyPlaceCategory category,
        int matchedCount,
        int returnedCount,
        boolean hasMore,
        Instant retrievedAt,
        List<NearbyPlaceItem> places) {}
