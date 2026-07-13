package com.home.application.place;

import java.time.Instant;
import java.util.List;

import com.home.domain.place.NearbyPlaceCategory;

public record NearbyPlaceCategoryResult(
	NearbyPlaceCategory category,
	int matchedCount,
	int returnedCount,
	boolean hasMore,
	Instant retrievedAt,
	List<NearbyPlaceItem> places
) {
}
