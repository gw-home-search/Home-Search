package com.home.application.read;

import java.time.LocalDate;

public record ComplexSummaryResult(
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
