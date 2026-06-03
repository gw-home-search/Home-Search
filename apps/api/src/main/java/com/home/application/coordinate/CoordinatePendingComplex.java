package com.home.application.coordinate;

import java.time.OffsetDateTime;

public record CoordinatePendingComplex(
	Long parcelId,
	Long complexId,
	String pnu,
	String aptSeq,
	String aptName,
	String address,
	CoordinatePendingReason reason,
	Long tradeCount,
	OffsetDateTime createdAt
) {
}
