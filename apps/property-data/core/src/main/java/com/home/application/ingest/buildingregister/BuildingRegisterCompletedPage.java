package com.home.application.ingest.buildingregister;

import java.util.List;

public record BuildingRegisterCompletedPage(int totalCount, List<BuildingRegisterRecordSnapshotCommand> records) {
    public BuildingRegisterCompletedPage {
        if (totalCount < 0) throw new IllegalArgumentException("totalCount must not be negative");
        records = records == null ? List.of() : List.copyOf(records);
    }
}
