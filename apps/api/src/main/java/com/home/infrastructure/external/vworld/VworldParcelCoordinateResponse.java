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

		BigDecimal minLongitude = null;
		BigDecimal minLatitude = null;
		BigDecimal maxLongitude = null;
		BigDecimal maxLatitude = null;

		for (Feature feature : features) {
			Optional<Bbox> bbox = feature.bbox(pnu);
			if (bbox.isEmpty()) {
				continue;
			}
			minLongitude = min(minLongitude, bbox.get().minLongitude());
			minLatitude = min(minLatitude, bbox.get().minLatitude());
			maxLongitude = max(maxLongitude, bbox.get().maxLongitude());
			maxLatitude = max(maxLatitude, bbox.get().maxLatitude());
		}

		if (minLongitude == null || minLatitude == null || maxLongitude == null || maxLatitude == null) {
			return Optional.empty();
		}

		return Optional.of(new ParcelCoordinate(
			minLatitude.add(maxLatitude).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64),
			minLongitude.add(maxLongitude).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64)
		));
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Feature(
		List<BigDecimal> bbox,
		Properties properties
	) {

		private Optional<Bbox> bbox(String expectedPnu) {
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

			return Optional.of(new Bbox(minLongitude, minLatitude, maxLongitude, maxLatitude));
		}
	}

	private record Bbox(
		BigDecimal minLongitude,
		BigDecimal minLatitude,
		BigDecimal maxLongitude,
		BigDecimal maxLatitude
	) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Properties(
		String pnu
	) {
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static BigDecimal min(BigDecimal current, BigDecimal candidate) {
		if (current == null) {
			return candidate;
		}
		return current.compareTo(candidate) <= 0 ? current : candidate;
	}

	private static BigDecimal max(BigDecimal current, BigDecimal candidate) {
		if (current == null) {
			return candidate;
		}
		return current.compareTo(candidate) >= 0 ? current : candidate;
	}
}
