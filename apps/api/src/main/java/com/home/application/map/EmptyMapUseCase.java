package com.home.application.map;

import java.util.List;

import org.springframework.stereotype.Service;

import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;
import com.home.infrastructure.web.map.dto.RegionMarkerResponse;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

@Service
public class EmptyMapUseCase implements MapUseCase {

	@Override
	public List<ComplexMarkerResponse> getComplexMarkers(ComplexMarkersRequest request) {
		return List.of();
	}

	@Override
	public List<RegionMarkerResponse> getRegionMarkers(RegionMarkersRequest request) {
		return List.of();
	}
}
