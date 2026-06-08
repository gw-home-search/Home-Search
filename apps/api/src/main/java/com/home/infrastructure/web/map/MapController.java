package com.home.infrastructure.web.map;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.home.application.map.ComplexMarkerQuery;
import com.home.application.map.ComplexMarkerResult;
import com.home.application.map.MapUseCase;
import com.home.application.map.RegionMarkerQuery;
import com.home.application.map.RegionMarkerResult;
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
		return ResponseEntity.ok(mapUseCase.getComplexMarkers(toQuery(request))
			.stream()
			.map(MapController::toResponse)
			.toList());
	}

	@PostMapping("/regions")
	public ResponseEntity<List<RegionMarkerResponse>> getRegionMarkers(
		@Valid @RequestBody RegionMarkersRequest request
	) {
		return ResponseEntity.ok(mapUseCase.getRegionMarkers(toQuery(request))
			.stream()
			.map(MapController::toResponse)
			.toList());
	}

	private static ComplexMarkerQuery toQuery(ComplexMarkersRequest request) {
		return new ComplexMarkerQuery(
			request.swLat(),
			request.swLng(),
			request.neLat(),
			request.neLng(),
			request.pyeongMin(),
			request.pyeongMax(),
			request.priceEokMin(),
			request.priceEokMax(),
			request.ageMin(),
			request.ageMax(),
			request.unitMin(),
			request.unitMax()
		);
	}

	private static RegionMarkerQuery toQuery(RegionMarkersRequest request) {
		return new RegionMarkerQuery(
			request.swLat(),
			request.swLng(),
			request.neLat(),
			request.neLng(),
			request.region()
		);
	}

	private static ComplexMarkerResponse toResponse(ComplexMarkerResult result) {
		return new ComplexMarkerResponse(
			result.parcelId(),
			result.complexId(),
			result.name(),
			result.lat(),
			result.lng(),
			result.latestDealAmount(),
			result.unitCntSum()
		);
	}

	private static RegionMarkerResponse toResponse(RegionMarkerResult result) {
		return new RegionMarkerResponse(
			result.id(),
			result.name(),
			result.lat(),
			result.lng(),
			result.trend()
		);
	}
}
