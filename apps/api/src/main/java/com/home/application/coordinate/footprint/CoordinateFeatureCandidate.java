package com.home.application.coordinate.footprint;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record CoordinateFeatureCandidate(
	String pnu,
	List<BigDecimal> bbox
) {

	public CoordinateFeatureCandidate {
		bbox = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNullElse(bbox, List.of())));
	}
}
