package com.home.application.prediction;

import java.util.Map;

public record PredictionFeatureSnapshot(
        Map<String, Object> numericFeatures, Map<String, String> embeddingFeatures, double baseLogValue) {}
