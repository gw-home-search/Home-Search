package com.home.application.map;

public record MapMarkerProjectionGeneration(
        long generationId,
        String sourceWatermark,
        long complexMarkerCount,
        long regionMarkerCount,
        String markerHash) {}
