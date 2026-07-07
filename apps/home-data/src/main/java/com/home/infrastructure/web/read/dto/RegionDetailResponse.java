package com.home.infrastructure.web.read.dto;

import java.util.List;

public record RegionDetailResponse(
	Long id,
	String name,
	Double latitude,
	Double longitude,
	List<RegionSummaryResponse> children
) {
}
