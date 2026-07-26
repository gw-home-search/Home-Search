package com.home.domain.complex.buildingprofile;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class BuildingProfileEffectiveValuePolicy {
    public static final BigDecimal RATIO_TOLERANCE = new BigDecimal("0.01");
    public static final BigDecimal AREA_TOLERANCE = new BigDecimal("0.001");

    public BuildingProfileDecimalDecision pnuConsensus(List<BigDecimal> rootValues, BigDecimal tolerance) {
        List<BigDecimal> values = safeValues(rootValues);
        if (values.isEmpty() || tolerance == null || tolerance.signum() < 0) {
            return missing(BuildingProfileConflictStatus.INCOMPLETE);
        }
        BigDecimal reference = values.getFirst();
        boolean agrees = values.stream()
                .allMatch(value -> value.subtract(reference, MathContext.DECIMAL128).abs().compareTo(tolerance) <= 0);
        if (!agrees) {
            return missing(BuildingProfileConflictStatus.SOURCE_CONFLICT);
        }
        return new BuildingProfileDecimalDecision(
                reference,
                BuildingProfilePublicScope.PARCEL,
                BuildingProfilePublicQuality.PNU_FALLBACK,
                BuildingProfileConflictStatus.NONE,
                true);
    }

    public BuildingProfileDecimalDecision completeSum(List<BigDecimal> contributorValues, int expectedCount) {
        List<BigDecimal> values = safeValues(contributorValues);
        if (expectedCount <= 0 || values.size() != expectedCount) {
            return missing(BuildingProfileConflictStatus.INCOMPLETE);
        }
        BigDecimal sum = values.stream()
                .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MathContext.DECIMAL128));
        return verified(sum);
    }

    public BuildingProfileDecimalDecision completeSum(
            List<BuildingProfileAreaContribution> contributions,
            int expectedCount,
            java.util.function.Function<BuildingProfileAreaContribution, BigDecimal> valueSelector) {
        List<BuildingProfileAreaContribution> safe = contributions == null ? List.of() : List.copyOf(contributions);
        if (safe.size() != expectedCount || expectedCount <= 0) {
            return missing(BuildingProfileConflictStatus.INCOMPLETE);
        }
        Set<String> managementKeys = new HashSet<>();
        List<BigDecimal> values = safe.stream()
                .filter(value -> value != null && managementKeys.add(value.managementKey()))
                .map(valueSelector)
                .filter(Objects::nonNull)
                .toList();
        return values.size() == expectedCount
                ? completeSum(values, expectedCount)
                : missing(BuildingProfileConflictStatus.INCOMPLETE);
    }

    public BuildingProfileDecimalDecision maximum(List<BigDecimal> contributorValues, int expectedCount) {
        List<BigDecimal> values = safeValues(contributorValues);
        if (values.isEmpty() || expectedCount <= 0 || values.size() > expectedCount) {
            return missing(BuildingProfileConflictStatus.INCOMPLETE);
        }
        BigDecimal maximum = values.stream().max(BigDecimal::compareTo).orElseThrow();
        if (values.size() == expectedCount) {
            return verified(maximum);
        }
        return new BuildingProfileDecimalDecision(
                maximum,
                BuildingProfilePublicScope.COMPLEX,
                BuildingProfilePublicQuality.PARTIAL,
                BuildingProfileConflictStatus.INCOMPLETE,
                false);
    }

    private static List<BigDecimal> safeValues(List<BigDecimal> values) {
        if (values == null || values.stream().anyMatch(Objects::isNull)) {
            return List.of();
        }
        return List.copyOf(values);
    }

    private static BuildingProfileDecimalDecision verified(BigDecimal value) {
        return new BuildingProfileDecimalDecision(
                value,
                BuildingProfilePublicScope.COMPLEX,
                BuildingProfilePublicQuality.VERIFIED,
                BuildingProfileConflictStatus.NONE,
                true);
    }

    private static BuildingProfileDecimalDecision missing(BuildingProfileConflictStatus status) {
        return new BuildingProfileDecimalDecision(null, null, null, status, false);
    }
}
