package com.home.domain.complex.buildingregister;

import java.math.BigDecimal;

public record BuildingRegisterRecord(
        String managementKey,
        String parentManagementKey,
        int registerKindCode,
        String mainAttachmentCode,
        String mainPurposeCode,
        BigDecimal platArea,
        BigDecimal archArea,
        BigDecimal totalArea,
        BigDecimal vlRatEstmTotArea,
        BigDecimal bcRat,
        BigDecimal vlRat) {
    public BuildingRegisterRecord {
        if (managementKey == null || managementKey.isBlank()) {
            throw new IllegalArgumentException("building register management key must not be blank");
        }
        managementKey = managementKey.trim();
        parentManagementKey = trimToNull(parentManagementKey);
        mainAttachmentCode = trimToNull(mainAttachmentCode);
        mainPurposeCode = trimToNull(mainPurposeCode);
    }

    public BuildingRegisterRecord withRegisterKindCode(int value) {
        return new BuildingRegisterRecord(
                managementKey,
                parentManagementKey,
                value,
                mainAttachmentCode,
                mainPurposeCode,
                platArea,
                archArea,
                totalArea,
                vlRatEstmTotArea,
                bcRat,
                vlRat);
    }

    public boolean isAggregateTitleFor(String rootManagementKey) {
        return isAggregateTitle() && rootManagementKey != null && rootManagementKey.equals(parentManagementKey);
    }

    public boolean isAggregateTitle() {
        return registerKindCode == 2 || registerKindCode == 3;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
