package com.home.infrastructure.web.regionnavigation;

import com.home.application.regionnavigation.RegionNavigationService;
import com.home.infrastructure.web.read.dto.ComplexSummaryResponse;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RegionNavigationController {

    private final RegionNavigationService regionNavigationService;

    public RegionNavigationController(RegionNavigationService regionNavigationService) {
        this.regionNavigationService = regionNavigationService;
    }

    @GetMapping("/api/v1/region")
    public ResponseEntity<List<RegionSummaryResponse>> getRootRegions() {
        return ResponseEntity.ok(regionNavigationService.getRootRegions().stream()
                .map(RegionSummaryResponse::from)
                .toList());
    }

    @GetMapping("/api/v1/region/{regionId}")
    public ResponseEntity<RegionDetailResponse> getRegionDetail(@PathVariable Long regionId) {
        return ResponseEntity.ok(RegionDetailResponse.from(regionNavigationService.getRegionDetail(regionId)));
    }

    @GetMapping("/api/v1/region/{regionId}/complexes")
    public ResponseEntity<List<ComplexSummaryResponse>> getRegionComplexes(
            @PathVariable Long regionId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        return ResponseEntity.ok(regionNavigationService.getRegionComplexes(regionId, limit, offset).stream()
                .map(ComplexSummaryResponse::from)
                .toList());
    }
}
