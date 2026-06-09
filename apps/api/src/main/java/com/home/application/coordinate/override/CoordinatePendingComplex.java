package com.home.application.coordinate.override;

import java.time.OffsetDateTime;

import com.home.domain.coordinate.CoordinatePendingReason;

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
