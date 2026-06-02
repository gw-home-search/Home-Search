package com.home.infrastructure.external.vworld;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.home.application.coordinate.BuildingFootprintImportCandidate;

@JsonIgnoreProperties(ignoreUnknown = true)
record VworldBuildingFootprintResponse(
	Integer totalCount,
	Integer totalFeatures,
	List<Feature> features
) {

	int totalCountOr(int fallback) {
		if (totalCount != null && totalCount > 0) {
			return totalCount;
		}
		if (totalFeatures != null && totalFeatures > 0) {
			return totalFeatures;
		}
		return fallback;
	}

	List<BuildingFootprintImportCandidate> footprints(String expectedPnu, String source, String snapshotVersion) {
		if (features == null || features.isEmpty()) {
			return List.of();
		}
		ArrayList<BuildingFootprintImportCandidate> results = new ArrayList<>();
		for (Feature feature : features) {
			feature.footprint(expectedPnu, source, snapshotVersion).ifPresent(results::add);
		}
		return results;
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Feature(
		String id,
		List<BigDecimal> bbox,
		Properties properties
	) {

		private Optional<BuildingFootprintImportCandidate> footprint(
			String expectedPnu,
			String source,
			String snapshotVersion
		) {
			if (properties == null || !expectedPnu.equals(trimToNull(properties.pnu()))) {
				return Optional.empty();
			}
			if (bbox == null || bbox.size() < 4) {
				return Optional.empty();
			}
			String sourceBuildingKey = firstText(id, properties.gisIdntfcNo(), properties.refrnSystmCntcNo());
			if (sourceBuildingKey == null) {
				return Optional.empty();
			}
			BigDecimal minLongitude = bbox.get(0);
			BigDecimal minLatitude = bbox.get(1);
			BigDecimal maxLongitude = bbox.get(2);
			BigDecimal maxLatitude = bbox.get(3);
			if (minLongitude == null || minLatitude == null || maxLongitude == null || maxLatitude == null) {
				return Optional.empty();
			}
			return Optional.of(new BuildingFootprintImportCandidate(
				expectedPnu,
				trimToNull(properties.buldNm()),
				trimToNull(properties.dongNm()),
				sourceBuildingKey,
				minLatitude.add(maxLatitude).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64),
				minLongitude.add(maxLongitude).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64),
				source,
				snapshotVersion
			));
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Properties(
		String pnu,
		@com.fasterxml.jackson.annotation.JsonProperty("buld_nm") String buldNm,
		@com.fasterxml.jackson.annotation.JsonProperty("dong_nm") String dongNm,
		@com.fasterxml.jackson.annotation.JsonProperty("gis_idntfc_no") String gisIdntfcNo,
		@com.fasterxml.jackson.annotation.JsonProperty("refrn_systm_cntc_no") String refrnSystmCntcNo
	) {
	}

	private static String firstText(String... values) {
		for (String value : values) {
			String normalized = trimToNull(value);
			if (normalized != null) {
				return normalized;
			}
		}
		return null;
	}

	private static String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
