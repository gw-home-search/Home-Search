package com.home.infrastructure.persistence.map;

import java.math.BigDecimal;

import com.home.application.map.ComplexMarkerQuery;
import com.home.domain.coordinate.CoordinateDisplayPolicy;

import org.springframework.jdbc.core.simple.JdbcClient;

record ComplexMarkerJdbcParameters(
	Double swLat,
	Double swLng,
	Double neLat,
	Double neLng,
	Long unitMin,
	Long unitMax,
	BigDecimal priceMin,
	BigDecimal priceMax,
	BigDecimal areaMin,
	BigDecimal areaMax,
	Integer ageMin,
	Integer ageMax,
	Integer trustedBuildingCoordinateConfidence
) {

	private static final BigDecimal TRADE_AMOUNT_UNITS_PER_EOK = BigDecimal.valueOf(10_000L);
	private static final BigDecimal SQUARE_METERS_PER_PYEONG = new BigDecimal("3.305785");

	static ComplexMarkerJdbcParameters from(ComplexMarkerQuery query) {
		return new ComplexMarkerJdbcParameters(
			query.swLat(),
			query.swLng(),
			query.neLat(),
			query.neLng(),
			query.unitMin(),
			query.unitMax(),
			eokToTradeAmount(query.priceEokMin()),
			eokToTradeAmount(query.priceEokMax()),
			pyeongToSquareMeters(query.pyeongMin()),
			pyeongToSquareMeters(query.pyeongMax()),
			query.ageMin(),
			query.ageMax(),
			CoordinateDisplayPolicy.TRUSTED_BUILDING_FOOTPRINT_CONFIDENCE
		);
	}

	JdbcClient.StatementSpec bindTradeFirst(JdbcClient.StatementSpec statement) {
		return bindCommon(statement);
	}

	JdbcClient.StatementSpec bindMarkerShapeFilter(JdbcClient.StatementSpec statement) {
		return bindCommon(statement)
			.param("unitMin", unitMin)
			.param("unitMax", unitMax)
			.param("ageMin", ageMin)
			.param("ageMax", ageMax);
	}

	private JdbcClient.StatementSpec bindCommon(JdbcClient.StatementSpec statement) {
		return statement
			.param("swLat", swLat)
			.param("swLng", swLng)
			.param("neLat", neLat)
			.param("neLng", neLng)
			.param("trustedBuildingCoordinateConfidence", trustedBuildingCoordinateConfidence)
			.param("priceMin", priceMin)
			.param("priceMax", priceMax)
			.param("areaMin", areaMin)
			.param("areaMax", areaMax);
	}

	private static BigDecimal eokToTradeAmount(Double eok) {
		return eok == null ? null : BigDecimal.valueOf(eok).multiply(TRADE_AMOUNT_UNITS_PER_EOK);
	}

	private static BigDecimal pyeongToSquareMeters(Integer pyeong) {
		return pyeong == null ? null : BigDecimal.valueOf(pyeong).multiply(SQUARE_METERS_PER_PYEONG);
	}
}
