package com.home.application.ingest.buildingregister;

import java.util.List;

public record ParsedBuildingRegisterPage(
        String resultCode, String resultMessage, int totalCount, List<BuildingRegisterRecordSnapshotCommand> records) {
    public ParsedBuildingRegisterPage {
        if (totalCount < 0) throw new IllegalArgumentException("totalCount must not be negative");
        records = records == null ? List.of() : List.copyOf(records);
    }

    public boolean providerSuccessful() {
        return "00".equals(resultCode) || "000".equals(resultCode) || "NORMAL_CODE".equals(resultCode);
    }
}
