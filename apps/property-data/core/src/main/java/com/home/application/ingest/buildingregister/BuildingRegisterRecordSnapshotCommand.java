package com.home.application.ingest.buildingregister;

import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record BuildingRegisterRecordSnapshotCommand(
        int itemIndex,
        String pnu,
        BuildingRegisterEndpoint endpoint,
        String managementKey,
        String parentManagementKey,
        String registerGroupCode,
        String registerKindCode,
        String newOldRegisterCode,
        String mainAttachedCode,
        String buildingName,
        String dongName,
        String mainPurposeCode,
        BigDecimal platArea,
        BigDecimal archArea,
        BigDecimal totalArea,
        BigDecimal floorRatioEstimateTotalArea,
        BigDecimal buildingCoverageRatio,
        BigDecimal floorAreaRatio,
        Integer mainBuildingCount,
        Integer attachedBuildingCount,
        Integer householdCount,
        LocalDate useApprovalDate,
        LocalDate creationDate) {
    public BuildingRegisterRecordSnapshotCommand {
        if (itemIndex < 0) throw new IllegalArgumentException("itemIndex must not be negative");
        if (pnu == null || !pnu.matches("[0-9]{19}")) throw new IllegalArgumentException("pnu must be 19 digits");
        Objects.requireNonNull(endpoint, "endpoint");
        if (managementKey == null || managementKey.isBlank()) {
            throw new IllegalArgumentException("managementKey must not be blank");
        }
        if (parentManagementKey != null && parentManagementKey.isBlank()) {
            throw new IllegalArgumentException("parentManagementKey must be null or nonblank");
        }
    }
}
