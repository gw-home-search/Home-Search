package com.home.infrastructure.persistence.ingest;

import java.util.Optional;

@FunctionalInterface
public interface ParcelCoordinateSnapshotRepository {

	Optional<ParcelCoordinate> findByPnu(String pnu);
}
