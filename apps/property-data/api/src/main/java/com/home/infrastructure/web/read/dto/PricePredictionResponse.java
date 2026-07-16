package com.home.infrastructure.web.read.dto;

import com.home.application.prediction.PricePredictionResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PricePredictionResponse(
        String status,
        String modelVersion,
        Long predictedDealAmount,
        BigDecimal predictedPricePerM2,
        BigDecimal predictedPricePerPyeong,
        Long intervalLow,
        Long intervalHigh,
        String intervalBasis,
        BigDecimal targetAreaM2,
        Integer targetFloor,
        Long basisTradeId,
        LocalDate basisDealDate,
        Instant generatedAt,
        String message) {

    public static PricePredictionResponse from(PricePredictionResult result) {
        if (result == null) {
            return null;
        }
        return new PricePredictionResponse(
                result.status().name(),
                result.modelVersion(),
                result.predictedDealAmount(),
                result.predictedPricePerM2(),
                result.predictedPricePerPyeong(),
                result.intervalLow(),
                result.intervalHigh(),
                result.intervalBasis(),
                result.targetAreaM2(),
                result.targetFloor(),
                result.basisTradeId(),
                result.basisDealDate(),
                result.generatedAt(),
                result.message());
    }

    public static PricePredictionResponse failed(String message) {
        return new PricePredictionResponse(
                "FAILED", null, null, null, null, null, null, null, null, null, null, null, Instant.now(), message);
    }
}
