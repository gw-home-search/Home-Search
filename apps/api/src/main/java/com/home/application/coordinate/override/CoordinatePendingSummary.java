package com.home.application.coordinate.override;

import java.util.Map;

public record CoordinatePendingSummary(
	long totalCount,
	Map<CoordinatePendingReason, Long> reasonCounts
) {

	public CoordinatePendingSummary(
		long totalCount,
		long pnuCoordinateMissingCount,
		long samePnuMultiComplexCount,
		long complexDisplayCoordinateMissingCount
	) {
		this(totalCount, Map.of(
			CoordinatePendingReason.PNU_COORDINATE_MISSING, pnuCoordinateMissingCount,
			CoordinatePendingReason.SAME_PNU_MULTI_COMPLEX, samePnuMultiComplexCount,
			CoordinatePendingReason.COMPLEX_DISPLAY_COORDINATE_MISSING, complexDisplayCoordinateMissingCount
		));
	}

	public long count(CoordinatePendingReason reason) {
		return reasonCounts.getOrDefault(reason, 0L);
	}
}
