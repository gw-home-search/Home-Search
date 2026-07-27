package com.home.application.map;

import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class MapMarkerProjectionRefreshService {

    private final MapMarkerProjectionRepository repository;
    private final MapMarkerSourceWatermarkReader watermarkReader;

    public MapMarkerProjectionRefreshService(
            MapMarkerProjectionRepository repository, MapMarkerSourceWatermarkReader watermarkReader) {
        this.repository = Objects.requireNonNull(repository);
        this.watermarkReader = Objects.requireNonNull(watermarkReader);
    }

    public MapMarkerProjectionGeneration refresh(String sourceWatermark) {
        if (sourceWatermark == null || sourceWatermark.isBlank()) {
            throw new IllegalArgumentException("Map marker source watermark must not be blank");
        }
        return repository.rebuildAndActivate(sourceWatermark.trim());
    }

    public long activeGenerationId() {
        return repository.activeGenerationId();
    }

    public MapMarkerProjectionGeneration refreshCurrent() {
        return refresh(watermarkReader.currentWatermark());
    }
}
