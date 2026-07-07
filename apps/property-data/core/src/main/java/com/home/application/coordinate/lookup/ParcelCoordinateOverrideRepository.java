package com.home.application.coordinate.lookup;

import java.util.Optional;

@FunctionalInterface
public interface ParcelCoordinateOverrideRepository {

	Optional<ParcelCoordinate> findApprovedByPnu(String pnu);

	static ParcelCoordinateOverrideRepository empty() {
		return pnu -> Optional.empty();
	}
}
