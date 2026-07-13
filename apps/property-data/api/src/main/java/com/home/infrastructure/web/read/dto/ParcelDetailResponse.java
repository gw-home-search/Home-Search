package com.home.infrastructure.web.read.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.home.application.read.ParcelDetailResult;

public record ParcelDetailResponse(
	Long parcelId,
	Long complexId,
	Double latitude,
	Double longitude,
	String address,
	String displayName,
	String tradeName,
	String name,
	Integer dongCnt,
	Integer unitCnt,
	BigDecimal platArea,
	BigDecimal archArea,
	BigDecimal totArea,
	BigDecimal bcRat,
	BigDecimal vlRat,
	LocalDate useDate,
	PricePredictionResponse prediction
) {

	public static ParcelDetailResponse from(ParcelDetailResult result, PricePredictionResponse prediction) {
		return new ParcelDetailResponse(
			result.parcelId(),
			result.complexId(),
			result.latitude(),
			result.longitude(),
			result.address(),
			result.displayName(),
			result.tradeName(),
			result.name(),
			result.dongCnt(),
			result.unitCnt(),
			result.platArea(),
			result.archArea(),
			result.totArea(),
			result.bcRat(),
			result.vlRat(),
			result.useDate(),
			prediction
		);
	}
}
