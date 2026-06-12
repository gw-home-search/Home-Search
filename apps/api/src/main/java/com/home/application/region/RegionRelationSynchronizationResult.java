package com.home.application.region;

public record RegionRelationSynchronizationResult(
	boolean relationChanged,
	boolean unitCntChanged,
	boolean unmatchedParcelExists
) {
}
