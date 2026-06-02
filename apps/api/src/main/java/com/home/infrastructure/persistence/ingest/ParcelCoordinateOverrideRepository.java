package com.home.infrastructure.persistence.ingest;

import java.util.Optional;

@FunctionalInterface
public interface ParcelCoordinateOverrideRepository {

	Optional<ParcelCoordinate> findApprovedByPnu(String pnu);

	static ParcelCoordinateOverrideRepository empty() {
		return pnu -> Optional.empty();
	}
}
