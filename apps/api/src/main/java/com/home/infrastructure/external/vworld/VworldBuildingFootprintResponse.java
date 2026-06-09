package com.home.infrastructure.external.vworld;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.home.application.coordinate.footprint.BuildingFootprintFeatureCandidate;
import com.home.application.coordinate.footprint.BuildingFootprintImportCandidate;
import com.home.application.coordinate.footprint.CoordinateGeometryPolicy;

@JsonIgnoreProperties(ignoreUnknown = true)
record VworldBuildingFootprintResponse(
	Integer totalCount,
	Integer totalFeatures,
	List<Feature> features
) {

	private static final CoordinateGeometryPolicy GEOMETRY_POLICY = new CoordinateGeometryPolicy();

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
		return GEOMETRY_POLICY.footprints(expectedPnu, source, snapshotVersion, features == null ? List.of() : features
			.stream()
			.map(Feature::candidate)
			.toList());
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Feature(
		String id,
		List<BigDecimal> bbox,
		Properties properties
	) {

		private BuildingFootprintFeatureCandidate candidate() {
			return new BuildingFootprintFeatureCandidate(
				id,
				properties == null ? null : properties.pnu(),
				properties == null ? null : properties.buldNm(),
				properties == null ? null : properties.dongNm(),
				properties == null
					? List.of()
					: java.util.Arrays.asList(properties.gisIdntfcNo(), properties.refrnSystmCntcNo()),
				bbox
			);
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

}
