package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRatioField;
import java.util.List;
import java.util.Set;

public record BuildingRegisterCollectionResult(
        BuildingRegisterCollectionStatus status,
        int requestCount,
        List<BuildingRegisterRecordSnapshotCommand> recapRecords,
        List<BuildingRegisterRecordSnapshotCommand> titleRecords,
        List<BuildingRegisterRecordSnapshotCommand> basicOverviewRecords,
        Set<BuildingRatioField> fallbackFields) {
    public BuildingRegisterCollectionResult {
        recapRecords = recapRecords == null ? List.of() : List.copyOf(recapRecords);
        titleRecords = titleRecords == null ? List.of() : List.copyOf(titleRecords);
        basicOverviewRecords = basicOverviewRecords == null ? List.of() : List.copyOf(basicOverviewRecords);
        fallbackFields = fallbackFields == null ? Set.of() : Set.copyOf(fallbackFields);
    }
}
