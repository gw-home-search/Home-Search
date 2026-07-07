package com.home.application.prediction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PredictionFeature(
	Long complexId,
	Long basisTradeId,
	LocalDate basisDealDate,
	BigDecimal targetAreaM2,
	Integer targetFloor,
	Map<String, Object> numericFeatures,
	Map<String, String> embeddingFeatures,
	double baseLogValue
) {

	public PredictionFeature {
		Objects.requireNonNull(complexId, "complexId must not be null");
		Objects.requireNonNull(basisTradeId, "basisTradeId must not be null");
		Objects.requireNonNull(basisDealDate, "basisDealDate must not be null");
		Objects.requireNonNull(targetAreaM2, "targetAreaM2 must not be null");
		numericFeatures = Collections.unmodifiableMap(new LinkedHashMap<>(
			Objects.requireNonNull(numericFeatures, "numericFeatures must not be null")
		));
		embeddingFeatures = Collections.unmodifiableMap(new LinkedHashMap<>(
			Objects.requireNonNull(embeddingFeatures, "embeddingFeatures must not be null")
		));
	}
}
