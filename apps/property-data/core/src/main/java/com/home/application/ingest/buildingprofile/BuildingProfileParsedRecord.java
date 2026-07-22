package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileTypedValue;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.util.Map;

public record BuildingProfileParsedRecord(
        int itemIndex,
        String pnu,
        BuildingRegisterEndpoint endpoint,
        Map<BuildingProfileField, BuildingProfileTypedValue> values) {
    public BuildingProfileParsedRecord {
        if (itemIndex < 0) throw new IllegalArgumentException("itemIndex must not be negative");
        if (pnu == null || !pnu.matches("[0-9]{19}")) throw new IllegalArgumentException("pnu must be 19 digits");
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public BuildingProfileTypedValue value(BuildingProfileField field) {
        return values.get(field);
    }
}
