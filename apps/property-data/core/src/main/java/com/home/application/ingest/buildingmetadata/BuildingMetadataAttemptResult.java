package com.home.application.ingest.buildingmetadata;

import com.home.domain.complex.metadata.ComplexMetadataStatus;

public record BuildingMetadataAttemptResult(ComplexMetadataStatus status, boolean projectionApplied) {}
