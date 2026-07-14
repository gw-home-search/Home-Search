package com.home.application.ingest.buildingmetadata;

import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;

public record BuildingMetadataSourceResponse(
        BuildingMetadataSourceKind sourceKind,
        String requestedPnu,
        Integer httpStatus,
        String resultCode,
        String body,
        Long observedBodyByteSize,
        String observedResponseHash,
        boolean payloadOversized) {
    public BuildingMetadataSourceResponse(
            BuildingMetadataSourceKind sourceKind,
            String requestedPnu,
            Integer httpStatus,
            String resultCode,
            String body) {
        this(sourceKind, requestedPnu, httpStatus, resultCode, body, null, null, false);
    }
}
