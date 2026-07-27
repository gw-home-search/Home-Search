package com.home.application.ingest.buildingprofile;

import java.util.List;
import java.util.UUID;

public interface BuildingProfileRepairRepository {
    List<BuildingProfileCollectTarget> freezeOrLoad(BuildingProfileRepairCommand command);

    int transientFailureCount(UUID collectionId, String pnu);

    void recordProgress(UUID collectionId, int requestCount, int completedCount, int failureCount, boolean completed);
}
