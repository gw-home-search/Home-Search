package com.home.application.map;

import java.util.List;
import java.util.Objects;

import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;
import com.home.infrastructure.web.map.dto.RegionMarkerResponse;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

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
	public List<ComplexMarkerResponse> getComplexMarkers(ComplexMarkersRequest request) {
		return complexMarkerRepository.findComplexMarkers(request);
	}

	@Override
	public List<RegionMarkerResponse> getRegionMarkers(RegionMarkersRequest request) {
		return regionMarkerRepository.findRegionMarkers(request);
	}
}
