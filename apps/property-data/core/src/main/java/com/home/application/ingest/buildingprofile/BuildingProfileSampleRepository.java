package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileHierarchyReason;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface BuildingProfileSampleRepository {
    List<BuildingProfileCollectTarget> freezeOrLoad(BuildingProfileCollectCommand command);

    Set<String> completedPnus(UUID collectionId);

    Optional<BuildingProfileCodeTransition> codeTransition(String originalPnu);

    void recordCodeLookup(UUID collectionId, BuildingProfileCodeLookupEvidence evidence);

    void recordCollected(UUID collectionId, String pnu, Set<BuildingProfileHierarchyReason> reasons);

    void recordFailure(UUID collectionId, String pnu, String failureStatus);

    boolean completeIfAllPnusCollected(UUID collectionId);
}
