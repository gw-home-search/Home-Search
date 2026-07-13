package com.home.infrastructure.web.read.dto;

import com.home.application.read.RegionSummaryResult;

public record RegionSummaryResponse(
	Long id,
	String name
) {

	public static RegionSummaryResponse from(RegionSummaryResult result) {
		return new RegionSummaryResponse(result.id(), result.name());
	}
}
