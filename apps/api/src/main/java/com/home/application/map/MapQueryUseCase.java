package com.home.application.map;

import java.util.List;
import java.util.Objects;

public class MapQueryUseCase implements MapUseCase {

	private final ComplexMarkerRepository complexMarkerRepository;
	private final RegionMarkerRepository regionMarkerRepository;

	public MapQueryUseCase(
		ComplexMarkerRepository complexMarkerRepository,
		RegionMarkerRepository regionMarkerRepository
	) {
		this.complexMarkerRepository = Objects.requireNonNull(complexMarkerRepository);
		this.regionMarkerRepository = Objects.requireNonNull(regionMarkerRepository);
	}

	@Override
	public List<ComplexMarkerResult> getComplexMarkers(ComplexMarkerQuery query) {
		return complexMarkerRepository.findComplexMarkers(query);
	}

	@Override
	public List<RegionMarkerResult> getRegionMarkers(RegionMarkerQuery query) {
		return regionMarkerRepository.findRegionMarkers(query);
	}
}
