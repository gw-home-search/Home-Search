package com.home.application.read;

import java.util.List;

public record RegionDetailResult(
	Long id,
	String name,
	Double latitude,
	Double longitude,
	List<RegionSummaryResult> children
) {
}
