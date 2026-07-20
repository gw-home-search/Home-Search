package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterCollectionMode;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record BuildingRegisterCampaignCommand(
        UUID collectionId,
        UUID requestId,
        LocalDate runDate,
        BuildingRegisterCollectionMode mode,
        BuildingRegisterCollectionStrategy strategy,
        int maxRequests,
        Long fromComplexId,
        long toComplexId) {
    public BuildingRegisterCampaignCommand {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(runDate, "runDate");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(strategy, "strategy");
        if (maxRequests <= 0 || toComplexId <= 0) throw new IllegalArgumentException("limits must be positive");
        if (fromComplexId != null && (fromComplexId <= 0 || fromComplexId > toComplexId)) {
            throw new IllegalArgumentException("invalid complex range");
        }
    }
}
