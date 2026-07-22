package com.home.domain.complex.buildingregister;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record BuildingRatioCandidate(
        BuildingRatioField field,
        BuildingRatioResolutionMethod method,
        BigDecimal value,
        BigDecimal numerator,
        BigDecimal denominator,
        List<String> inputManagementKeys) {
    private static final BigDecimal MAX_STORED_RATIO = new BigDecimal("9999.99");

    public BuildingRatioCandidate {
        if (field == null || method == null) throw new IllegalArgumentException("candidate identity is required");
        if (value == null || value.signum() <= 0 || projected(value).compareTo(MAX_STORED_RATIO) > 0) {
            throw new IllegalArgumentException("candidate ratio must fit complex ratio storage");
        }
        inputManagementKeys = inputManagementKeys == null ? List.of() : List.copyOf(inputManagementKeys);
    }

    public BigDecimal projectedValue() {
        return projected(value);
    }

    private static BigDecimal projected(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
