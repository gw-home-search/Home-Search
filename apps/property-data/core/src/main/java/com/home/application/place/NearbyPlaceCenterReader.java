package com.home.application.place;

import java.util.Optional;

@FunctionalInterface
public interface NearbyPlaceCenterReader {

	Optional<NearbyPlaceCenter> findComplexCenter(Long complexId);
}
