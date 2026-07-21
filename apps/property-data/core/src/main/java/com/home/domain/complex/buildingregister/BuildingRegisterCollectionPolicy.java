package com.home.domain.complex.buildingregister;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class BuildingRegisterCollectionPolicy {
    private final BuildingRatioEvaluator evaluator = new BuildingRatioEvaluator();

    public BuildingRegisterFollowUpDecision afterRecap(
            BuildingRegisterCollectionStrategy strategy, List<BuildingRegisterRecord> recaps, int pnuComplexCount) {
        if (strategy == null) throw new IllegalArgumentException("collection strategy is required");
        if (pnuComplexCount <= 0) throw new IllegalArgumentException("PNU complex count must be positive");
        List<BuildingRegisterRecord> safeRecaps = recaps == null ? List.of() : List.copyOf(recaps);
        if (strategy == BuildingRegisterCollectionStrategy.COMPARE_RECAP_TITLE) {
            return new BuildingRegisterFollowUpDecision(true, pnuComplexCount > 1 || safeRecaps.size() > 1, Set.of());
        }
        if (strategy == BuildingRegisterCollectionStrategy.FULL_HIERARCHY) {
            return new BuildingRegisterFollowUpDecision(true, true, EnumSet.allOf(BuildingRatioField.class));
        }
        if (safeRecaps.isEmpty()) {
            return new BuildingRegisterFollowUpDecision(true, false, EnumSet.allOf(BuildingRatioField.class));
        }
        if (safeRecaps.size() != 1 || pnuComplexCount != 1) {
            return new BuildingRegisterFollowUpDecision(true, true, EnumSet.allOf(BuildingRatioField.class));
        }
        BuildingRegisterRecord recap = safeRecaps.get(0);
        EnumSet<BuildingRatioField> missing = EnumSet.noneOf(BuildingRatioField.class);
        for (BuildingRatioField field : BuildingRatioField.values()) {
            if (!positive(field.directRatio(recap))) missing.add(field);
        }
        return new BuildingRegisterFollowUpDecision(!missing.isEmpty(), !missing.isEmpty(), missing);
    }

    public boolean shouldFetchBasicOverview(
            BuildingRegisterCollectionStrategy strategy,
            List<BuildingRegisterRecord> recaps,
            List<BuildingRegisterRecord> titles,
            int pnuComplexCount,
            Set<BuildingRatioField> fallbackFields) {
        if (strategy == null) throw new IllegalArgumentException("collection strategy is required");
        if (pnuComplexCount <= 0) throw new IllegalArgumentException("PNU complex count must be positive");
        List<BuildingRegisterRecord> safeRecaps = recaps == null ? List.of() : List.copyOf(recaps);
        List<BuildingRegisterRecord> safeTitles = titles == null ? List.of() : List.copyOf(titles);
        Set<BuildingRatioField> safeFallbackFields = fallbackFields == null ? Set.of() : Set.copyOf(fallbackFields);
        if (strategy == BuildingRegisterCollectionStrategy.FULL_HIERARCHY) return true;
        if (safeRecaps.isEmpty()) return safeTitles.size() > 1;
        if (safeRecaps.size() != 1 || pnuComplexCount != 1) return true;
        if (safeFallbackFields.isEmpty()) return false;

        List<BuildingRegisterRecord> aggregateTitles = safeTitles.stream()
                .filter(BuildingRegisterRecord::isAggregateTitle)
                .toList();
        if (aggregateTitles.isEmpty()) return false;
        Set<String> expectedKeys = aggregateTitles.stream()
                .map(BuildingRegisterRecord::managementKey)
                .collect(Collectors.toSet());
        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                strategy, safeRecaps.getFirst(), aggregateTitles, expectedKeys, true));
        return safeFallbackFields.stream()
                .map(evaluation::field)
                .flatMap(field -> field.candidates().stream())
                .anyMatch(candidate -> candidate.method().usesTitleHierarchyEvidence());
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
