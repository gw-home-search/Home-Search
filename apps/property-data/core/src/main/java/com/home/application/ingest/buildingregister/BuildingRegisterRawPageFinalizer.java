package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuildingRegisterRawPageFinalizer implements BuildingRegisterRawPageCompletion {
    private final BuildingRegisterRawPageRepository repository;
    private final BuildingRegisterEndpointSnapshotStore snapshots;

    public BuildingRegisterRawPageFinalizer(
            BuildingRegisterRawPageRepository repository, BuildingRegisterEndpointSnapshotStore snapshots) {
        this.repository = Objects.requireNonNull(repository);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    @Transactional
    @Override
    public void complete(
            long rawPageId,
            long endpointSnapshotId,
            Integer totalCount,
            BuildingRegisterRawPageStatus status,
            List<BuildingRegisterRecordSnapshotCommand> records) {
        repository.complete(rawPageId, status, records);
        if (totalCount != null) snapshots.observeTotalCount(endpointSnapshotId, totalCount);
    }
}
