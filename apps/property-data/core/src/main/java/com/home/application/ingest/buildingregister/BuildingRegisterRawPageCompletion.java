package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.util.List;

@FunctionalInterface
public interface BuildingRegisterRawPageCompletion {
    void complete(
            long rawPageId, BuildingRegisterRawPageStatus status, List<BuildingRegisterRecordSnapshotCommand> records);
}
