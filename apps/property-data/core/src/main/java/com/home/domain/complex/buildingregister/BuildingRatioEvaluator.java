package com.home.domain.complex.buildingregister;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BuildingRatioEvaluator {
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_PROJECTED_DIFFERENCE = new BigDecimal("0.01");
    private static final BigDecimal MAX_STORED_RATIO = new BigDecimal("9999.99");

    public BuildingRatioEvaluation evaluate(BuildingRatioEvaluationContext context) {
        if (context == null) throw new IllegalArgumentException("ratio evaluation context is required");
        EnumMap<BuildingRatioField, BuildingRatioFieldEvaluation> fields = new EnumMap<>(BuildingRatioField.class);
        for (BuildingRatioField field : BuildingRatioField.values()) {
            List<BuildingRatioCandidate> candidates = candidates(context, field);
            fields.put(field, select(context.scope(), field, candidates));
        }
        return new BuildingRatioEvaluation(fields);
    }

    private List<BuildingRatioCandidate> candidates(BuildingRatioEvaluationContext context, BuildingRatioField field) {
        List<BuildingRatioCandidate> candidates = new ArrayList<>();
        if (context.scope() == BuildingRatioScope.STANDALONE_TITLE) {
            addStandaloneCandidates(candidates, field, context.titles().get(0));
            return List.copyOf(candidates);
        }

        BuildingRegisterRecord recap = context.recap();
        addDirect(candidates, field, BuildingRatioResolutionMethod.RECAP_DIRECT, recap);
        addCalculated(candidates, field, BuildingRatioResolutionMethod.RECAP_COMPONENT_CALC, recap);

        boolean titleFallbackAllowed = context.strategy() == BuildingRegisterCollectionStrategy.FULL_HIERARCHY
                || !positive(field.directRatio(recap));
        if (!titleFallbackAllowed) return List.copyOf(candidates);

        List<BuildingRegisterRecord> completeTitles = completeTitles(context);
        if (completeTitles.isEmpty()) return List.copyOf(candidates);
        BigDecimal denominator = consensusDenominator(completeTitles);
        addDirectConsensus(candidates, field, completeTitles);
        if (!positive(recap.platArea()) && denominator != null && field.numerator(recap) != null) {
            addCalculated(
                    candidates,
                    field,
                    BuildingRatioResolutionMethod.RECAP_NUMERATOR_TITLE_DENOMINATOR,
                    field.numerator(recap),
                    denominator,
                    hybridInputKeys(recap, completeTitles));
        }
        addTitleAggregate(candidates, field, completeTitles, denominator);
        return List.copyOf(candidates);
    }

    private void addStandaloneCandidates(
            List<BuildingRatioCandidate> candidates, BuildingRatioField field, BuildingRegisterRecord title) {
        addDirect(candidates, field, BuildingRatioResolutionMethod.STANDALONE_TITLE_DIRECT, title);
        addCalculated(candidates, field, BuildingRatioResolutionMethod.STANDALONE_TITLE_COMPONENT_CALC, title);
    }

    private void addDirect(
            List<BuildingRatioCandidate> candidates,
            BuildingRatioField field,
            BuildingRatioResolutionMethod method,
            BuildingRegisterRecord record) {
        BigDecimal value = field.directRatio(record);
        if (!validRatio(value)) return;
        candidates.add(new BuildingRatioCandidate(field, method, value, null, null, List.of(record.managementKey())));
    }

    private void addCalculated(
            List<BuildingRatioCandidate> candidates,
            BuildingRatioField field,
            BuildingRatioResolutionMethod method,
            BuildingRegisterRecord record) {
        addCalculated(
                candidates, field, method, field.numerator(record), record.platArea(), List.of(record.managementKey()));
    }

    private void addCalculated(
            List<BuildingRatioCandidate> candidates,
            BuildingRatioField field,
            BuildingRatioResolutionMethod method,
            BigDecimal numerator,
            BigDecimal denominator,
            List<String> inputKeys) {
        if (numerator == null || numerator.signum() < 0 || !positive(denominator)) return;
        BigDecimal value =
                numerator.divide(denominator, MathContext.DECIMAL128).multiply(HUNDRED, MathContext.DECIMAL128);
        if (!validRatio(value)) return;
        candidates.add(new BuildingRatioCandidate(field, method, value, numerator, denominator, inputKeys));
    }

    private void addDirectConsensus(
            List<BuildingRatioCandidate> candidates, BuildingRatioField field, List<BuildingRegisterRecord> titles) {
        List<BigDecimal> direct = titles.stream().map(field::directRatio).toList();
        if (direct.stream().anyMatch(value -> !validRatio(value))) return;
        BigDecimal agreed = direct.get(0).setScale(2, RoundingMode.HALF_UP);
        if (direct.stream()
                .anyMatch(value -> value.setScale(2, RoundingMode.HALF_UP).compareTo(agreed) != 0)) return;
        candidates.add(new BuildingRatioCandidate(
                field, BuildingRatioResolutionMethod.TITLE_DIRECT_CONSENSUS, agreed, null, null, inputKeys(titles)));
    }

    private void addTitleAggregate(
            List<BuildingRatioCandidate> candidates,
            BuildingRatioField field,
            List<BuildingRegisterRecord> titles,
            BigDecimal denominator) {
        if (denominator == null) return;
        List<BigDecimal> numerators = titles.stream().map(field::numerator).toList();
        if (numerators.stream().anyMatch(value -> value == null || value.signum() < 0)) return;
        BigDecimal numerator = numerators.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        addCalculated(
                candidates,
                field,
                BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC,
                numerator,
                denominator,
                inputKeys(titles));
    }

    private List<BuildingRegisterRecord> completeTitles(BuildingRatioEvaluationContext context) {
        if (!context.hierarchyComplete() || context.expectedTitleKeys().isEmpty()) return List.of();
        String rootKey = context.recap().managementKey();
        Map<String, List<BuildingRegisterRecord>> byKey = new HashMap<>();
        context.titles().stream()
                .filter(record -> record.isAggregateTitleFor(rootKey))
                .filter(record -> context.expectedTitleKeys().contains(record.managementKey()))
                .forEach(record -> byKey.computeIfAbsent(record.managementKey(), ignored -> new ArrayList<>())
                        .add(record));
        for (String expectedKey : context.expectedTitleKeys()) {
            if (byKey.getOrDefault(expectedKey, List.of()).size() != 1) return List.of();
        }
        return context.expectedTitleKeys().stream()
                .sorted()
                .map(key -> byKey.get(key).get(0))
                .toList();
    }

    private BigDecimal consensusDenominator(List<BuildingRegisterRecord> titles) {
        List<BigDecimal> denominators =
                titles.stream().map(BuildingRegisterRecord::platArea).toList();
        if (denominators.stream().anyMatch(value -> !positive(value))) return null;
        BigDecimal agreed = denominators.get(0).setScale(3, RoundingMode.HALF_UP);
        if (denominators.stream()
                .anyMatch(value -> value.setScale(3, RoundingMode.HALF_UP).compareTo(agreed) != 0)) return null;
        return agreed;
    }

    private BuildingRatioFieldEvaluation select(
            BuildingRatioScope scope, BuildingRatioField field, List<BuildingRatioCandidate> candidates) {
        if (candidates.isEmpty()) {
            return new BuildingRatioFieldEvaluation(
                    field, BuildingRatioResolutionStatus.SOURCE_MISSING, null, candidates);
        }
        BigDecimal minimum = candidates.stream()
                .map(BuildingRatioCandidate::projectedValue)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        BigDecimal maximum = candidates.stream()
                .map(BuildingRatioCandidate::projectedValue)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        if (maximum.subtract(minimum).compareTo(MAX_PROJECTED_DIFFERENCE) > 0) {
            return new BuildingRatioFieldEvaluation(
                    field, BuildingRatioResolutionStatus.SOURCE_CONFLICT, null, candidates);
        }
        BuildingRatioCandidate selected = candidates.stream()
                .min(Comparator.comparingInt(candidate -> candidate.method().ordinal()))
                .orElseThrow();
        BuildingRatioResolutionStatus status = scope.projectable()
                ? BuildingRatioResolutionStatus.SELECTED
                : BuildingRatioResolutionStatus.SKIPPED_SHARED_SCOPE;
        return new BuildingRatioFieldEvaluation(field, status, selected, candidates);
    }

    private List<String> inputKeys(List<BuildingRegisterRecord> records) {
        return records.stream()
                .map(BuildingRegisterRecord::managementKey)
                .sorted()
                .toList();
    }

    private List<String> hybridInputKeys(BuildingRegisterRecord recap, List<BuildingRegisterRecord> titles) {
        List<String> keys = new ArrayList<>(inputKeys(titles));
        keys.add(recap.managementKey());
        return keys.stream().sorted().toList();
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean validRatio(BigDecimal value) {
        return positive(value) && value.setScale(2, RoundingMode.HALF_UP).compareTo(MAX_STORED_RATIO) <= 0;
    }
}
