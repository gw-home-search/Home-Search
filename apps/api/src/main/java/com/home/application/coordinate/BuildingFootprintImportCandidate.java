package com.home.application.coordinate;

import java.math.BigDecimal;
import java.util.Objects;

public record BuildingFootprintImportCandidate(
	String pnu,
	String buildingName,
	String dongName,
	String sourceBuildingKey,
	BigDecimal latitude,
	BigDecimal longitude,
	String source,
	String snapshotVersion
) {

	public BuildingFootprintImportCandidate {
		Objects.requireNonNull(pnu, "pnu is required");
		Objects.requireNonNull(sourceBuildingKey, "sourceBuildingKey is required");
		Objects.requireNonNull(latitude, "latitude is required");
		Objects.requireNonNull(longitude, "longitude is required");
		Objects.requireNonNull(source, "source is required");
		Objects.requireNonNull(snapshotVersion, "snapshotVersion is required");
	}
}
