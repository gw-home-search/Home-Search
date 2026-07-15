package com.home.application.prediction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record PricePredictionResult(
        PredictionStatus status,
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

    public PricePredictionResult {
        Objects.requireNonNull(status, "status must not be null");
    }

    static PricePredictionResult pending(
            PredictionFeature feature, PredictionProperties properties, Instant generatedAt) {
        return new PricePredictionResult(
                PredictionStatus.PENDING,
                properties.modelVersion(),
                null,
                null,
                null,
                null,
                null,
                properties.intervalBasis(),
                feature.targetAreaM2(),
                feature.targetFloor(),
                feature.basisTradeId(),
                feature.basisDealDate(),
                generatedAt,
                null);
    }

    static PricePredictionResult ready(
            PredictionFeature feature,
            PredictionProperties properties,
            PredictionClientResult clientResult,
            Instant generatedAt) {
        return new PricePredictionResult(
                PredictionStatus.READY,
                firstNonBlank(clientResult.modelVersion(), properties.modelVersion()),
                clientResult.predictedDealAmount(),
                clientResult.predictedPricePerM2(),
                clientResult.predictedPricePerPyeong(),
                clientResult.intervalLow(),
                clientResult.intervalHigh(),
                firstNonBlank(clientResult.intervalBasis(), properties.intervalBasis()),
                feature.targetAreaM2(),
                feature.targetFloor(),
                feature.basisTradeId(),
                feature.basisDealDate(),
                generatedAt,
                null);
    }

    static PricePredictionResult failed(
            PredictionFeature feature, PredictionProperties properties, Instant generatedAt, String message) {
        return new PricePredictionResult(
                PredictionStatus.FAILED,
                properties.modelVersion(),
                null,
                null,
                null,
                null,
                null,
                properties.intervalBasis(),
                feature.targetAreaM2(),
                feature.targetFloor(),
                feature.basisTradeId(),
                feature.basisDealDate(),
                generatedAt,
                message);
    }

    static PricePredictionResult unavailable(Instant generatedAt, String message) {
        return new PricePredictionResult(
                PredictionStatus.UNAVAILABLE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                generatedAt,
                message);
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
