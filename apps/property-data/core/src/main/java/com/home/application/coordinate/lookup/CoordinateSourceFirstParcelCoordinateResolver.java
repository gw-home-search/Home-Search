package com.home.application.coordinate.lookup;

import java.util.Objects;
import java.util.Optional;

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
	public Optional<ParcelCoordinate> resolve(String pnu) {
		Optional<ParcelCoordinate> sourceCoordinate = coordinateSourceRepository.findByPnu(pnu);
		if (sourceCoordinate.isPresent()) {
			return sourceCoordinate;
		}
		return overrideRepository.findApprovedByPnu(pnu);
	}
}
