package com.home.infrastructure.external.vworld;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.home.application.coordinate.footprint.CoordinateCenter;
import com.home.application.coordinate.footprint.CoordinateFeatureCandidate;
import com.home.application.coordinate.footprint.CoordinateGeometryPolicy;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VworldParcelCoordinateResponse(
	List<Feature> features
) {

	private static final CoordinateGeometryPolicy GEOMETRY_POLICY = new CoordinateGeometryPolicy();

	Optional<CoordinateCenter> center(String pnu) {
		return GEOMETRY_POLICY.parcelCenter(pnu, features == null ? List.of() : features.stream()
			.map(Feature::candidate)
			.toList());
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Feature(
		List<BigDecimal> bbox,
		Properties properties
	) {

		private CoordinateFeatureCandidate candidate() {
			return new CoordinateFeatureCandidate(properties == null ? null : properties.pnu(), bbox);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Properties(
		String pnu
	) {
	}

}
