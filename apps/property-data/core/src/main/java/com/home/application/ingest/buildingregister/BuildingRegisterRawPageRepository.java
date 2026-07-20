package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import java.util.List;

public interface BuildingRegisterRawPageRepository {
    long receive(BuildingRegisterRawPageReceiptCommand command);

    void complete(
            long rawPageId, BuildingRegisterRawPageStatus status, List<BuildingRegisterRecordSnapshotCommand> records);

    String body(long rawPageId);
}
