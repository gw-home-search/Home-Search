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
        long toComplexId,
        int parallelism) {
    public BuildingRegisterCampaignCommand(
            UUID collectionId,
            UUID requestId,
            LocalDate runDate,
            BuildingRegisterCollectionMode mode,
            BuildingRegisterCollectionStrategy strategy,
            int maxRequests,
            Long fromComplexId,
            long toComplexId) {
        this(collectionId, requestId, runDate, mode, strategy, maxRequests, fromComplexId, toComplexId, 1);
    }

    public BuildingRegisterCampaignCommand {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(runDate, "runDate");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(strategy, "strategy");
        if (maxRequests <= 0 || toComplexId <= 0) throw new IllegalArgumentException("limits must be positive");
        if (parallelism <= 0 || parallelism > 4) {
            throw new IllegalArgumentException("parallelism must be between 1 and 4");
        }
        if (fromComplexId != null && (fromComplexId <= 0 || fromComplexId > toComplexId)) {
            throw new IllegalArgumentException("invalid complex range");
        }
    }

    public BuildingRegisterCollectCommand collectCommand(String pnu, int pnuComplexCount, int requestLimit) {
        return new BuildingRegisterCollectCommand(
                collectionId, requestId, runDate, pnu, pnuComplexCount, strategy, requestLimit);
    }
}
