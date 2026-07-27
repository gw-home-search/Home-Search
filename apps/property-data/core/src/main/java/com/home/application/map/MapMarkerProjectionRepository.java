package com.home.application.map;

public interface MapMarkerProjectionRepository {

    MapMarkerProjectionGeneration rebuildAndActivate(String sourceWatermark);

    long activeGenerationId();
}
