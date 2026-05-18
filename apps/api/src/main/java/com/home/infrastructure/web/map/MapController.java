package com.home.infrastructure.web.map;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.home.application.map.MapUseCase;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;
import com.home.infrastructure.web.map.dto.RegionMarkerResponse;
import com.home.infrastructure.web.map.dto.RegionMarkersRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/map")
public class MapController {

	private final MapUseCase mapUseCase;

	public MapController(MapUseCase mapUseCase) {
		this.mapUseCase = mapUseCase;
	}

	@PostMapping("/complexes")
	public ResponseEntity<List<ComplexMarkerResponse>> getComplexMarkers(
		@Valid @RequestBody ComplexMarkersRequest request
	) {
		return ResponseEntity.ok(mapUseCase.getComplexMarkers(request));
	}

	@PostMapping("/regions")
	public ResponseEntity<List<RegionMarkerResponse>> getRegionMarkers(
		@Valid @RequestBody RegionMarkersRequest request
	) {
		return ResponseEntity.ok(mapUseCase.getRegionMarkers(request));
	}
}
