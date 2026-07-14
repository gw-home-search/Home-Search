package com.home.application.place;

import com.home.domain.place.NearbyPlaceCategory;
import java.util.List;

public interface NearbyPlaceUseCase {

    NearbyPlacesResult getNearbyPlaces(
            Long complexId, Integer radiusMeters, List<NearbyPlaceCategory> categories, Integer limitPerCategory);
}
