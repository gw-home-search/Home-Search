package com.home.infrastructure.web.read.dto;

import java.time.LocalDate;

public record ComplexSummaryResponse(
	Long complexId,
	String complexName,
	Long parcelId,
	Double latitude,
	Double longitude,
	String address,
	Integer dongCnt,
	Integer unitCnt,
	LocalDate useDate
) {
}
