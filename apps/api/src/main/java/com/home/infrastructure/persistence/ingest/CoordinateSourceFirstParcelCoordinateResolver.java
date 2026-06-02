package com.home.infrastructure.persistence.ingest;

import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.OpenApiTradeItem;

public class CoordinateSourceFirstParcelCoordinateResolver implements ParcelCoordinateResolver {

	private final ParcelCoordinateSourceRepository coordinateSourceRepository;
	private final ParcelCoordinateOverrideRepository overrideRepository;

	public CoordinateSourceFirstParcelCoordinateResolver(
		ParcelCoordinateSourceRepository coordinateSourceRepository,
		ParcelCoordinateOverrideRepository overrideRepository
	) {
		this.coordinateSourceRepository = Objects.requireNonNull(coordinateSourceRepository);
		this.overrideRepository = Objects.requireNonNull(overrideRepository);
	}

	@Override
	public Optional<ParcelCoordinate> resolve(String pnu, OpenApiTradeItem item) {
		Optional<ParcelCoordinate> sourceCoordinate = coordinateSourceRepository.findByPnu(pnu);
		if (sourceCoordinate.isPresent()) {
			return sourceCoordinate;
		}
		return overrideRepository.findApprovedByPnu(pnu);
	}
}
