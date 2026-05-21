package com.home.infrastructure.web.read.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParcelDetailResponse(
	Long parcelId,
	Double latitude,
	Double longitude,
	String address,
	String tradeName,
	String name,
	Integer dongCnt,
	Integer unitCnt,
	BigDecimal platArea,
	BigDecimal archArea,
	BigDecimal totArea,
	BigDecimal bcRat,
	BigDecimal vlRat,
	LocalDate useDate
) {
}
