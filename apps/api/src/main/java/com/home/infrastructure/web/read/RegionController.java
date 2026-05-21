package com.home.infrastructure.web.read;

import java.util.List;

import com.home.application.read.MvpReadUseCase;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegionController {

	private final MvpReadUseCase readUseCase;

	public RegionController(MvpReadUseCase readUseCase) {
		this.readUseCase = readUseCase;
	}

	@GetMapping("/api/v1/region")
	public ResponseEntity<List<RegionSummaryResponse>> getRootRegions() {
		return ResponseEntity.ok(readUseCase.getRootRegions());
	}

	@GetMapping("/api/v1/region/{regionId}")
	public ResponseEntity<RegionDetailResponse> getRegionDetail(@PathVariable Long regionId) {
		return ResponseEntity.ok(readUseCase.getRegionDetail(regionId));
	}
}
