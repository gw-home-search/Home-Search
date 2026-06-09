package com.home.application.coordinate.footprint;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 외부 GIS feature bbox를 프로젝트 좌표 후보로 해석하는 application policy입니다.
 */
public class CoordinateGeometryPolicy {

	public Optional<CoordinateCenter> parcelCenter(String expectedPnu, List<CoordinateFeatureCandidate> features) {
		if (features == null || features.isEmpty()) {
			return Optional.empty();
		}

		BigDecimal minLongitude = null;
		BigDecimal minLatitude = null;
		BigDecimal maxLongitude = null;
		BigDecimal maxLatitude = null;

		for (CoordinateFeatureCandidate feature : features) {
			if (!pnuMatches(expectedPnu, feature.pnu())) {
				continue;
			}
			Optional<Bbox> bbox = bbox(feature.bbox());
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

		return Optional.of(new CoordinateCenter(
			center(minLatitude, maxLatitude),
			center(minLongitude, maxLongitude)
		));
	}

	public List<BuildingFootprintImportCandidate> footprints(
		String expectedPnu,
		String source,
		String snapshotVersion,
		List<BuildingFootprintFeatureCandidate> features
	) {
		if (features == null || features.isEmpty()) {
			return List.of();
		}
		ArrayList<BuildingFootprintImportCandidate> results = new ArrayList<>();
		for (BuildingFootprintFeatureCandidate feature : features) {
			footprint(expectedPnu, source, snapshotVersion, feature).ifPresent(results::add);
		}
		return results;
	}

	private Optional<BuildingFootprintImportCandidate> footprint(
		String expectedPnu,
		String source,
		String snapshotVersion,
		BuildingFootprintFeatureCandidate feature
	) {
		if (!expectedPnu.equals(trimToNull(feature.pnu()))) {
			return Optional.empty();
		}
		String sourceBuildingKey = firstText(feature.featureId(), feature.sourceBuildingKeys());
		if (sourceBuildingKey == null) {
			return Optional.empty();
		}
		Optional<Bbox> bbox = bbox(feature.bbox());
		if (bbox.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new BuildingFootprintImportCandidate(
			expectedPnu,
			trimToNull(feature.buildingName()),
			trimToNull(feature.dongName()),
			sourceBuildingKey,
			center(bbox.get().minLatitude(), bbox.get().maxLatitude()),
			center(bbox.get().minLongitude(), bbox.get().maxLongitude()),
			source,
			snapshotVersion
		));
	}

	private boolean pnuMatches(String expectedPnu, String candidatePnu) {
		String normalized = trimToNull(candidatePnu);
		return normalized == null || expectedPnu.equals(normalized);
	}

	private Optional<Bbox> bbox(List<BigDecimal> bbox) {
		if (bbox == null || bbox.size() < 4) {
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

	private BigDecimal center(BigDecimal first, BigDecimal second) {
		return first.add(second).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
	}

	private BigDecimal min(BigDecimal current, BigDecimal candidate) {
		if (current == null) {
			return candidate;
		}
		return current.compareTo(candidate) <= 0 ? current : candidate;
	}

	private BigDecimal max(BigDecimal current, BigDecimal candidate) {
		if (current == null) {
			return candidate;
		}
		return current.compareTo(candidate) >= 0 ? current : candidate;
	}

	private String firstText(String featureId, List<String> sourceBuildingKeys) {
		String normalizedFeatureId = trimToNull(featureId);
		if (normalizedFeatureId != null) {
			return normalizedFeatureId;
		}
		for (String value : sourceBuildingKeys) {
			String normalized = trimToNull(value);
			if (normalized != null) {
				return normalized;
			}
		}
		return null;
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}

	private record Bbox(
		BigDecimal minLongitude,
		BigDecimal minLatitude,
		BigDecimal maxLongitude,
		BigDecimal maxLatitude
	) {
	}
}
