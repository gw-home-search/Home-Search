package com.home.infrastructure.web.read;

import java.util.List;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.PropertyReadUseCase;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import com.home.infrastructure.web.read.dto.ComplexSummaryResponse;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegionController {

	private final PropertyReadUseCase readUseCase;

	public RegionController(PropertyReadUseCase readUseCase) {
		this.readUseCase = readUseCase;
	}

	@GetMapping("/api/v1/region")
	public ResponseEntity<List<RegionSummaryResponse>> getRootRegions() {
		return ResponseEntity.ok(readUseCase.getRootRegions()
			.stream()
			.map(RegionController::toResponse)
			.toList());
	}

	@GetMapping("/api/v1/region/{regionId}")
	public ResponseEntity<RegionDetailResponse> getRegionDetail(@PathVariable Long regionId) {
		return ResponseEntity.ok(toResponse(readUseCase.getRegionDetail(regionId)));
	}

	@GetMapping("/api/v1/region/{regionId}/complexes")
	public ResponseEntity<List<ComplexSummaryResponse>> getRegionComplexes(
		@PathVariable Long regionId,
		@RequestParam(value = "limit", required = false) Integer limit,
		@RequestParam(value = "offset", required = false) Integer offset
	) {
		return ResponseEntity.ok(readUseCase.getRegionComplexes(regionId, limit, offset)
			.stream()
			.map(RegionController::toResponse)
			.toList());
	}

	private static ComplexSummaryResponse toResponse(ComplexSummaryResult result) {
		return new ComplexSummaryResponse(
			result.complexId(),
			result.complexName(),
			result.parcelId(),
			result.latitude(),
			result.longitude(),
			result.address(),
			result.dongCnt(),
			result.unitCnt(),
			result.useDate()
		);
	}

	private static RegionDetailResponse toResponse(RegionDetailResult result) {
		return new RegionDetailResponse(
			result.id(),
			result.name(),
			result.latitude(),
			result.longitude(),
			result.children().stream()
				.map(RegionController::toResponse)
				.toList()
		);
	}

	private static RegionSummaryResponse toResponse(RegionSummaryResult result) {
		return new RegionSummaryResponse(
			result.id(),
			result.name()
		);
	}
}
