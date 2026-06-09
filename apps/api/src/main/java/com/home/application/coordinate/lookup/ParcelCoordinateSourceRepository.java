package com.home.application.coordinate.lookup;

import java.util.Optional;

public interface ParcelCoordinateSourceRepository {

	Optional<ParcelCoordinate> findByPnu(String pnu);

	static ParcelCoordinateSourceRepository empty() {
		return pnu -> Optional.empty();
	}
}
