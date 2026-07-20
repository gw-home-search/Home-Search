package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRatioProjectionOutcome;
import java.util.List;
import java.util.UUID;

public interface BuildingRatioProjectionRepository {
    boolean isCampaignCompleted(UUID collectionId);

    List<BuildingRatioProjectionTarget> findProjectionTargets(
            UUID collectionId, Long fromComplexId, Long toComplexId, int limit);

    BuildingRatioProjectionOutcome project(UUID requestId, BuildingRatioProjectionTarget target);
}
