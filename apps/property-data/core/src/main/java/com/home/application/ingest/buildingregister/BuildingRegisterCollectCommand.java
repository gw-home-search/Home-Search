package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record BuildingRegisterCollectCommand(
        UUID collectionId,
        UUID requestId,
        LocalDate runDate,
        String pnu,
        int pnuComplexCount,
        BuildingRegisterCollectionStrategy strategy,
        int maxRequests,
        boolean includeBasicOverview) {
    public BuildingRegisterCollectCommand(
            UUID collectionId,
            UUID requestId,
            LocalDate runDate,
            String pnu,
            int pnuComplexCount,
            BuildingRegisterCollectionStrategy strategy,
            int maxRequests) {
        this(collectionId, requestId, runDate, pnu, pnuComplexCount, strategy, maxRequests, true);
    }

    public BuildingRegisterCollectCommand {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(runDate, "runDate");
        if (pnu == null || !pnu.matches("[0-9]{19}")) throw new IllegalArgumentException("pnu must be 19 digits");
        if (pnuComplexCount <= 0) throw new IllegalArgumentException("pnuComplexCount must be positive");
        Objects.requireNonNull(strategy, "strategy");
        if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
    }
}
