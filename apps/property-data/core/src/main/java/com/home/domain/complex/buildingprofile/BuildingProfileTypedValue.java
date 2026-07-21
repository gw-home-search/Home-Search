package com.home.domain.complex.buildingprofile;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BuildingProfileTypedValue(
        BuildingProfileValueState state,
        String rawValue,
        String textValue,
        BigDecimal decimalValue,
        Long integerValue,
        LocalDate dateValue,
        Boolean booleanValue) {

    public static BuildingProfileTypedValue state(BuildingProfileValueState state, String rawValue) {
        return new BuildingProfileTypedValue(state, rawValue, null, null, null, null, null);
    }
}
