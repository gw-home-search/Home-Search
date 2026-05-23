package com.home.infrastructure.external.vworld;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.home.infrastructure.persistence.ingest.ParcelCoordinate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VworldParcelCoordinateResponse(
	List<Feature> features
) {

	Optional<ParcelCoordinate> center(String pnu) {
		if (features == null || features.isEmpty()) {
			return Optional.empty();
		}

		ParcelCoordinate matched = null;

		for (Feature feature : features) {
			Optional<ParcelCoordinate> center = feature.center(pnu);
			if (center.isEmpty()) {
				continue;
			}
			if (matched != null) {
				return Optional.empty();
			}
			matched = center.get();
		}

		if (matched == null) {
			return Optional.empty();
		}

		return Optional.of(matched);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Feature(
		List<BigDecimal> bbox,
		Properties properties
	) {

		private Optional<ParcelCoordinate> center(String expectedPnu) {
			if (bbox == null || bbox.size() < 4) {
				return Optional.empty();
			}
			if (properties != null && hasText(properties.pnu()) && !expectedPnu.equals(properties.pnu().trim())) {
				return Optional.empty();
			}

			BigDecimal minLongitude = bbox.get(0);
			BigDecimal minLatitude = bbox.get(1);
			BigDecimal maxLongitude = bbox.get(2);
			BigDecimal maxLatitude = bbox.get(3);
			if (minLongitude == null || minLatitude == null || maxLongitude == null || maxLatitude == null) {
				return Optional.empty();
			}

			return Optional.of(new ParcelCoordinate(
				minLatitude.add(maxLatitude).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64),
				minLongitude.add(maxLongitude).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64)
			));
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Properties(
		String pnu
	) {
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
