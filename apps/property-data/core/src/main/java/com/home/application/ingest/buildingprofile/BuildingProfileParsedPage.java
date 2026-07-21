package com.home.application.ingest.buildingprofile;

import java.util.List;
import java.util.Set;

public record BuildingProfileParsedPage(
        String resultCode,
        String resultMessage,
        int totalCount,
        List<BuildingProfileParsedRecord> records,
        Set<String> unknownKeys) {
    public BuildingProfileParsedPage {
        if (totalCount < 0) throw new IllegalArgumentException("totalCount must not be negative");
        records = records == null ? List.of() : List.copyOf(records);
        unknownKeys = unknownKeys == null ? Set.of() : Set.copyOf(unknownKeys);
    }

    public boolean providerSuccessful() {
        return List.of("00", "000", "NORMAL_CODE").contains(resultCode);
    }
}
