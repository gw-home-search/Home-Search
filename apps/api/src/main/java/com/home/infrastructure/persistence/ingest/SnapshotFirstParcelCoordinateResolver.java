package com.home.infrastructure.persistence.ingest;

import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.OpenApiTradeItem;

public class SnapshotFirstParcelCoordinateResolver implements ParcelCoordinateResolver {

	private final ParcelCoordinateSnapshotRepository snapshotRepository;
	private final ParcelCoordinateOverrideRepository overrideRepository;
	private final ParcelCoordinateResolver fallbackResolver;

	public SnapshotFirstParcelCoordinateResolver(
		ParcelCoordinateSnapshotRepository snapshotRepository,
		ParcelCoordinateResolver fallbackResolver
	) {
		this(snapshotRepository, ParcelCoordinateOverrideRepository.empty(), fallbackResolver);
	}

	public SnapshotFirstParcelCoordinateResolver(
		ParcelCoordinateSnapshotRepository snapshotRepository,
		ParcelCoordinateOverrideRepository overrideRepository,
		ParcelCoordinateResolver fallbackResolver
	) {
		this.snapshotRepository = Objects.requireNonNull(snapshotRepository);
		this.overrideRepository = Objects.requireNonNull(overrideRepository);
		this.fallbackResolver = Objects.requireNonNull(fallbackResolver);
	}

	@Override
	public Optional<ParcelCoordinate> resolve(String pnu, OpenApiTradeItem item) {
		Optional<ParcelCoordinate> snapshotCoordinate = snapshotRepository.findByPnu(pnu);
		if (snapshotCoordinate.isPresent()) {
			return snapshotCoordinate;
		}
		Optional<ParcelCoordinate> approvedOverride = overrideRepository.findApprovedByPnu(pnu);
		if (approvedOverride.isPresent()) {
			return approvedOverride;
		}
		return fallbackResolver.resolve(pnu, item);
	}
}
