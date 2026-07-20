package com.home.domain.complex.buildingregister;

public record BuildingRegisterHierarchyRecord(
        BuildingRegisterEndpoint endpoint,
        String managementKey,
        String parentManagementKey,
        int registerKindCode,
        String newOldRegisterCode,
        String buildingName,
        String dongName) {
    public BuildingRegisterHierarchyRecord {
        if (endpoint == null) throw new IllegalArgumentException("endpoint is required");
        if (managementKey == null || managementKey.isBlank()) {
            throw new IllegalArgumentException("managementKey is required");
        }
        managementKey = managementKey.trim();
        parentManagementKey = trim(parentManagementKey);
        newOldRegisterCode = trim(newOldRegisterCode);
        buildingName = trim(buildingName);
        dongName = trim(dongName);
    }

    public boolean isRoot() {
        return endpoint == BuildingRegisterEndpoint.RECAP_TITLE && registerKindCode == 1;
    }

    public boolean isTitleLike() {
        return endpoint == BuildingRegisterEndpoint.TITLE && (registerKindCode == 2 || registerKindCode == 3);
    }

    public boolean isExpectedChild() {
        return endpoint == BuildingRegisterEndpoint.BASIC_OVERVIEW && (registerKindCode == 2 || registerKindCode == 3);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }
}
