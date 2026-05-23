package com.home.infrastructure.persistence.ingest;

import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.OpenApiTradeItem;

public class SnapshotFirstParcelCoordinateResolver implements ParcelCoordinateResolver {

	private final ParcelCoordinateSnapshotRepository snapshotRepository;
	private final ParcelCoordinateResolver fallbackResolver;

	public SnapshotFirstParcelCoordinateResolver(
		ParcelCoordinateSnapshotRepository snapshotRepository,
		ParcelCoordinateResolver fallbackResolver
	) {
		this.snapshotRepository = Objects.requireNonNull(snapshotRepository);
		this.fallbackResolver = Objects.requireNonNull(fallbackResolver);
	}

	@Override
	public Optional<ParcelCoordinate> resolve(String pnu, OpenApiTradeItem item) {
		Optional<ParcelCoordinate> snapshotCoordinate = snapshotRepository.findByPnu(pnu);
		if (snapshotCoordinate.isPresent()) {
			return snapshotCoordinate;
		}
		return fallbackResolver.resolve(pnu, item);
	}
}
