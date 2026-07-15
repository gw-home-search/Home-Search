package com.home.application.place;

import com.home.domain.place.NearbyPlaceCategory;

public interface ViewportNearbyPlaceUseCase {

    ViewportNearbyPlacesResult getNearbyPlaces(NearbyPlaceBounds bounds, Integer level, NearbyPlaceCategory category);
}
