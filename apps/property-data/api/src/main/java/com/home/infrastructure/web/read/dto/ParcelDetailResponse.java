package com.home.infrastructure.web.read.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

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

	public ParcelDetailResponse(
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
		this(
			parcelId,
			complexId,
			latitude,
			longitude,
			address,
			name,
			tradeName,
			name,
			dongCnt,
			unitCnt,
			platArea,
			archArea,
			totArea,
			bcRat,
			vlRat,
			useDate,
			null
		);
	}

	public ParcelDetailResponse(
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
		this(
			parcelId,
			null,
			latitude,
			longitude,
			address,
			name,
			tradeName,
			name,
			dongCnt,
			unitCnt,
			platArea,
			archArea,
			totArea,
			bcRat,
			vlRat,
			useDate,
			null
		);
	}
}
