package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.util.List;

@FunctionalInterface
public interface BuildingRegisterRawPageCompletion {
    void complete(
            long rawPageId,
            long endpointSnapshotId,
            Integer totalCount,
            String providerStatus,
            BuildingRegisterRawPageStatus status,
            List<BuildingRegisterRecordSnapshotCommand> records);
}
