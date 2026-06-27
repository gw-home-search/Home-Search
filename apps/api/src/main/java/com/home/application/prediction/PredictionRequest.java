package com.home.application.prediction;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PredictionRequest(
	@JsonProperty("numeric_features")
	Map<String, Object> numericFeatures,
	@JsonProperty("embedding_features")
	Map<String, String> embeddingFeatures,
	@JsonProperty("base_log_value")
	double baseLogValue,
	@JsonProperty("area_m2")
	BigDecimal areaM2,
	@JsonProperty("interval_pct")
	BigDecimal intervalPct,
	@JsonProperty("interval_basis")
	String intervalBasis
) {

	public PredictionRequest {
		numericFeatures = Collections.unmodifiableMap(new LinkedHashMap<>(
			Objects.requireNonNull(numericFeatures, "numericFeatures must not be null")
		));
		embeddingFeatures = Collections.unmodifiableMap(new LinkedHashMap<>(
			Objects.requireNonNull(embeddingFeatures, "embeddingFeatures must not be null")
		));
		Objects.requireNonNull(areaM2, "areaM2 must not be null");
		Objects.requireNonNull(intervalPct, "intervalPct must not be null");
	}
}
