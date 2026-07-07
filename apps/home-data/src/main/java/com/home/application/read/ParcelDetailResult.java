package com.home.application.read;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParcelDetailResult(
	Long parcelId,
	Long complexId,
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
