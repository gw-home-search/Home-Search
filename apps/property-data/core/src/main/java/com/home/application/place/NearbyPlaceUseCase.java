package com.home.application.place;

import java.util.List;

import com.home.domain.place.NearbyPlaceCategory;

public interface NearbyPlaceUseCase {

	NearbyPlacesResult getNearbyPlaces(
		Long complexId,
		Integer radiusMeters,
		List<NearbyPlaceCategory> categories,
		Integer limitPerCategory
	);
}
