package com.home.application.coordinate.footprint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record BuildingFootprintFeatureCandidate(
	String featureId,
	String pnu,
	String buildingName,
	String dongName,
	List<String> sourceBuildingKeys,
	List<BigDecimal> bbox
) {

	public BuildingFootprintFeatureCandidate {
		sourceBuildingKeys = Objects.requireNonNullElse(sourceBuildingKeys, List.<String>of())
			.stream()
			.filter(Objects::nonNull)
			.toList();
		bbox = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNullElse(bbox, List.of())));
	}
}
