package com.home.application.prediction;

import java.math.BigDecimal;

public record PredictionClientResult(
	String modelVersion,
	BigDecimal predictedPricePerM2,
	Long predictedDealAmount,
	BigDecimal predictedPricePerPyeong,
	BigDecimal rawResidualLog,
	BigDecimal predictedLogPricePerM2,
	Long intervalLow,
	Long intervalHigh,
	String intervalBasis
) {
}
