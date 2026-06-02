package com.home.infrastructure.persistence.ingest;

import java.util.Optional;

public interface ParcelCoordinateSourceRepository {

	Optional<ParcelCoordinate> findByPnu(String pnu);

	static ParcelCoordinateSourceRepository empty() {
		return pnu -> Optional.empty();
	}
}
