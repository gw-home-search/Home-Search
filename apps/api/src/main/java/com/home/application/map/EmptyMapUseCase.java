package com.home.application.map;

import java.util.List;

import org.springframework.stereotype.Service;

import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;

@Service
public class EmptyMapUseCase implements MapUseCase {

	@Override
	public List<ComplexMarkerResponse> getComplexMarkers(ComplexMarkersRequest request) {
		return List.of();
	}
}
