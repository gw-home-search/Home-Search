package com.home.application.map;

import java.util.List;
import java.util.Objects;

import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;
import com.home.infrastructure.web.map.dto.RegionMarkerResponse;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

public class MapQueryUseCase implements MapUseCase {

	private final ComplexMarkerRepository complexMarkerRepository;

	public MapQueryUseCase(ComplexMarkerRepository complexMarkerRepository) {
		this.complexMarkerRepository = Objects.requireNonNull(complexMarkerRepository);
	}

	@Override
	public List<ComplexMarkerResponse> getComplexMarkers(ComplexMarkersRequest request) {
		return complexMarkerRepository.findComplexMarkers(request);
	}

	@Override
	public List<RegionMarkerResponse> getRegionMarkers(RegionMarkersRequest request) {
		return List.of();
	}
}
