package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface BuildingRegisterEndpointSnapshotStore {
    BuildingRegisterEndpointSnapshot open(
            UUID collectionId, String pnu, BuildingRegisterEndpoint endpoint, LocalDate runDate, int pageSize);

    Optional<BuildingRegisterCompletedPage> completedPage(long snapshotId, int pageNo);

    default void observeTotalCount(long snapshotId, int totalCount) {}

    void complete(long snapshotId, int totalCount, BuildingRegisterCollectionStatus status);

    void abandonOversized(long snapshotId, int pageSize, boolean permanent);
}
