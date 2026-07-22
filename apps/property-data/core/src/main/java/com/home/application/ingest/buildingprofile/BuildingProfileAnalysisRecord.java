package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileTypedValue;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.util.Map;

public record BuildingProfileAnalysisRecord(
        long recordId,
        String pnu,
        BuildingRegisterEndpoint endpoint,
        String managementKey,
        String parentManagementKey,
        int registerKindCode,
        String newOldRegisterCode,
        Map<BuildingProfileField, BuildingProfileTypedValue> values) {
    public BuildingProfileAnalysisRecord {
        values = Map.copyOf(values);
    }

    public BuildingProfileTypedValue value(BuildingProfileField field) {
        return values.get(field);
    }
}
