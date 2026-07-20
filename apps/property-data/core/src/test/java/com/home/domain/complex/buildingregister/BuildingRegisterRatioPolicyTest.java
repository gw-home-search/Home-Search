package com.home.domain.complex.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingRegisterRatioPolicyTest {
    private static final String ROOT = "ROOT-1";
    private final BuildingRegisterCollectionPolicy collectionPolicy = new BuildingRegisterCollectionPolicy();
    private final BuildingRatioEvaluator evaluator = new BuildingRatioEvaluator();

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void skipsFallbackWhenSingleRecapHasBothDirectRatios() {
        var decision = collectionPolicy.afterRecap(
                BuildingRegisterCollectionStrategy.ADAPTIVE, List.of(recap("1000", "200", "800", "20", "80")), 1);

        assertThat(decision.fetchTitles()).isFalse();
        assertThat(decision.fetchBasicOverview()).isFalse();
        assertThat(decision.fallbackFields()).isEmpty();
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void fetchesFallbackOnlyForMissingFloorAreaRatioAndKeepsCoverageFromRecap() {
        BuildingRegisterRecord recap = recap("1000", "200", null, "20", "0");
        var decision = collectionPolicy.afterRecap(BuildingRegisterCollectionStrategy.ADAPTIVE, List.of(recap), 1);

        assertThat(decision.fetchTitles()).isTrue();
        assertThat(decision.fetchBasicOverview()).isTrue();
        assertThat(decision.fallbackFields()).containsExactly(BuildingRatioField.FLOOR_AREA_RATIO);

        BuildingRegisterRecord title = title("T-1", ROOT, "1000", "200", "800", "20", "80", "02000");
        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.ADAPTIVE, recap, List.of(title), Set.of("T-1"), true));

        assertThat(evaluation
                        .field(BuildingRatioField.BUILDING_COVERAGE_RATIO)
                        .selectedCandidate()
                        .method())
                .isEqualTo(BuildingRatioResolutionMethod.RECAP_DIRECT);
        assertThat(evaluation
                        .field(BuildingRatioField.FLOOR_AREA_RATIO)
                        .selectedCandidate()
                        .method())
                .isEqualTo(BuildingRatioResolutionMethod.TITLE_DIRECT_CONSENSUS);
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void calculatesRecapComponentsWhenDirectRatiosAreMissing() {
        BuildingRegisterRecord recap = recap("1000", "200", "800", null, null);

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.ADAPTIVE, recap, List.of(), Set.of(), false));

        assertCandidate(
                evaluation,
                BuildingRatioField.BUILDING_COVERAGE_RATIO,
                BuildingRatioResolutionMethod.RECAP_COMPONENT_CALC,
                "20.00");
        assertCandidate(
                evaluation,
                BuildingRatioField.FLOOR_AREA_RATIO,
                BuildingRatioResolutionMethod.RECAP_COMPONENT_CALC,
                "80.00");
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void combinesRecapNumeratorWithConsensusTitleDenominator() {
        BuildingRegisterRecord recap = recap(null, "200", "800", null, null);
        List<BuildingRegisterRecord> titles = List.of(
                title("T-1", ROOT, "1000.0001", null, null, null, null, "02000"),
                title("T-2", ROOT, "1000.0004", null, null, null, null, "03000"));

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.ADAPTIVE, recap, titles, Set.of("T-1", "T-2"), true));

        assertCandidate(
                evaluation,
                BuildingRatioField.BUILDING_COVERAGE_RATIO,
                BuildingRatioResolutionMethod.RECAP_NUMERATOR_TITLE_DENOMINATOR,
                "20.00");
        assertCandidate(
                evaluation,
                BuildingRatioField.FLOOR_AREA_RATIO,
                BuildingRatioResolutionMethod.RECAP_NUMERATOR_TITLE_DENOMINATOR,
                "80.00");
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void createsTitleDirectConsensusWhenEveryExpectedTitleAgreesAtProjectionScale() {
        List<BuildingRegisterRecord> titles = List.of(
                title("T-1", ROOT, "1000", null, null, "20.001", "80.001", "02000"),
                title("T-2", ROOT, "1000", null, null, "20.004", "80.004", "03000"));

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.FULL_HIERARCHY,
                recap("1000", null, null, null, null),
                titles,
                Set.of("T-1", "T-2"),
                true));

        assertCandidate(
                evaluation,
                BuildingRatioField.BUILDING_COVERAGE_RATIO,
                BuildingRatioResolutionMethod.TITLE_DIRECT_CONSENSUS,
                "20.00");
        assertCandidate(
                evaluation,
                BuildingRatioField.FLOOR_AREA_RATIO,
                BuildingRatioResolutionMethod.TITLE_DIRECT_CONSENSUS,
                "80.00");
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void calculatesCompleteTitleAggregateEvenWhenTitleDirectRatiosDiffer() {
        List<BuildingRegisterRecord> titles = List.of(
                title("T-1", ROOT, "1000", "100", "300", "10", "30", "02000"),
                title("T-2", ROOT, "1000", "100", "500", "20", "90", "03000"));

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                recap("1000", null, null, null, null),
                titles,
                Set.of("T-1", "T-2"),
                true));

        assertCandidate(
                evaluation,
                BuildingRatioField.BUILDING_COVERAGE_RATIO,
                BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC,
                "20.00");
        assertCandidate(
                evaluation,
                BuildingRatioField.FLOOR_AREA_RATIO,
                BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC,
                "80.00");
    }

    @Test
    @DisplayName("기본개요로 확인한 표제부는 provider 상위 관리번호가 없어도 합산한다")
    void aggregatesExpectedTitlesWhenProviderOmitsParentManagementKey() {
        List<BuildingRegisterRecord> titles = List.of(
                title("T-1", null, "1000", "100", "300", null, null, "02000"),
                title("T-2", null, "1000", "100", "500", null, null, "03000"));

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.FULL_HIERARCHY,
                recap("1000", null, null, null, null),
                titles,
                Set.of("T-1", "T-2"),
                true));

        assertCandidate(
                evaluation,
                BuildingRatioField.BUILDING_COVERAGE_RATIO,
                BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC,
                "20.00");
        assertCandidate(
                evaluation,
                BuildingRatioField.FLOOR_AREA_RATIO,
                BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC,
                "80.00");
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void doesNotAggregateWhenAnExpectedTitleIsMissing() {
        BuildingRegisterRecord title = title("T-1", ROOT, "1000", "200", "800", null, null, "02000");

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                recap("1000", null, null, null, null),
                List.of(title),
                Set.of("T-1", "T-2"),
                true));

        assertThat(evaluation.field(BuildingRatioField.BUILDING_COVERAGE_RATIO).candidates())
                .noneMatch(candidate -> candidate.method() == BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC);
        assertThat(evaluation.field(BuildingRatioField.FLOOR_AREA_RATIO).candidates())
                .noneMatch(candidate -> candidate.method() == BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC);
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void neverUsesTotalAreaAsFloorAreaRatioNumerator() {
        BuildingRegisterRecord title = new BuildingRegisterRecord(
                "T-1", ROOT, 3, "0", "02000", decimal("1000"), decimal("200"), decimal("9999"), null, null, null);

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                recap("1000", null, null, null, null),
                List.of(title),
                Set.of("T-1"),
                true));

        assertThat(evaluation.field(BuildingRatioField.FLOOR_AREA_RATIO).candidates())
                .isEmpty();
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void includesNonApartmentChildPurposesInAggregate() {
        List<BuildingRegisterRecord> titles = List.of(
                title("T-1", ROOT, "1000", "150", "700", null, null, "02000"),
                title("T-2", ROOT, "1000", "50", "100", null, null, "03999"));

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                recap("1000", null, null, null, null),
                titles,
                Set.of("T-1", "T-2"),
                true));

        assertCandidate(
                evaluation,
                BuildingRatioField.BUILDING_COVERAGE_RATIO,
                BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC,
                "20.00");
        assertCandidate(
                evaluation,
                BuildingRatioField.FLOOR_AREA_RATIO,
                BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC,
                "80.00");
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void excludesUnitRecordsAndRecordsFromAnotherRoot() {
        List<BuildingRegisterRecord> titles = List.of(
                title("T-1", ROOT, "1000", "200", "800", null, null, "02000"),
                title("UNIT-1", ROOT, "1000", "900", "900", null, null, "02000").withRegisterKindCode(4),
                title("OTHER-1", "ROOT-2", "1000", "900", "900", null, null, "02000"));

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                recap("1000", null, null, null, null),
                titles,
                Set.of("T-1"),
                true));

        assertCandidate(
                evaluation,
                BuildingRatioField.BUILDING_COVERAGE_RATIO,
                BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC,
                "20.00");
        assertCandidate(
                evaluation,
                BuildingRatioField.FLOOR_AREA_RATIO,
                BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC,
                "80.00");
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void rejectsCandidatesThatDifferByMoreThanPointZeroOneAtProjectionScale() {
        BuildingRegisterRecord recap = recap("1000", "200", null, "20", null);
        BuildingRegisterRecord title = title("T-1", ROOT, "1000", "201", null, null, null, "02000");

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.FULL_HIERARCHY, recap, List.of(title), Set.of("T-1"), true));

        var result = evaluation.field(BuildingRatioField.BUILDING_COVERAGE_RATIO);
        assertThat(result.status()).isEqualTo(BuildingRatioResolutionStatus.SOURCE_CONFLICT);
        assertThat(result.selectedCandidate()).isNull();
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void sharedRecapCanKeepCandidateEvidenceButIsNeverProjectable() {
        BuildingRegisterRecord recap = recap("1000", "200", "800", "20", "80");

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.sharedRoot(
                BuildingRegisterCollectionStrategy.ADAPTIVE, recap, List.of(), Set.of(), false));

        var coverage = evaluation.field(BuildingRatioField.BUILDING_COVERAGE_RATIO);
        assertThat(coverage.selectedCandidate().method()).isEqualTo(BuildingRatioResolutionMethod.RECAP_DIRECT);
        assertThat(coverage.status()).isEqualTo(BuildingRatioResolutionStatus.SKIPPED_SHARED_SCOPE);
        assertThat(coverage.projectable()).isFalse();
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void selectsStandaloneDirectValueBeforeItsMatchingComponentCalculation() {
        BuildingRegisterRecord title = title("T-1", null, "1000", "200", "800", "20", "80", "02000");

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.standalone(title));

        assertCandidate(
                evaluation,
                BuildingRatioField.BUILDING_COVERAGE_RATIO,
                BuildingRatioResolutionMethod.STANDALONE_TITLE_DIRECT,
                "20.00");
        assertCandidate(
                evaluation,
                BuildingRatioField.FLOOR_AREA_RATIO,
                BuildingRatioResolutionMethod.STANDALONE_TITLE_DIRECT,
                "80.00");
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void acceptsCandidateDifferenceOfExactlyPointZeroOneAtProjectionScale() {
        BuildingRegisterRecord recap = recap("1000", "200", null, "20", null);
        BuildingRegisterRecord title = title("T-1", ROOT, "1000", "200.1", null, null, null, "02000");

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.FULL_HIERARCHY, recap, List.of(title), Set.of("T-1"), true));

        assertCandidate(
                evaluation,
                BuildingRatioField.BUILDING_COVERAGE_RATIO,
                BuildingRatioResolutionMethod.RECAP_DIRECT,
                "20.00");
    }

    @Test
    @DisplayName("건축물대장 비율 후보 해석 정책을 검증한다")
    void evaluatesTitleNumeratorCompletenessIndependentlyForEachRatioField() {
        List<BuildingRegisterRecord> titles = List.of(
                title("T-1", ROOT, "1000", "100", "300", null, null, "02000"),
                title("T-2", ROOT, "1000", "100", null, null, null, "03000"));

        var evaluation = evaluator.evaluate(BuildingRatioEvaluationContext.uniqueRoot(
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                recap("1000", null, null, null, null),
                titles,
                Set.of("T-1", "T-2"),
                true));

        assertCandidate(
                evaluation,
                BuildingRatioField.BUILDING_COVERAGE_RATIO,
                BuildingRatioResolutionMethod.TITLE_AGGREGATE_CALC,
                "20.00");
        assertThat(evaluation.field(BuildingRatioField.FLOOR_AREA_RATIO).status())
                .isEqualTo(BuildingRatioResolutionStatus.SOURCE_MISSING);
    }

    private void assertCandidate(
            BuildingRatioEvaluation evaluation,
            BuildingRatioField field,
            BuildingRatioResolutionMethod method,
            String projectedValue) {
        BuildingRatioFieldEvaluation fieldEvaluation = evaluation.field(field);
        assertThat(fieldEvaluation.status()).isEqualTo(BuildingRatioResolutionStatus.SELECTED);
        assertThat(fieldEvaluation.selectedCandidate().method()).isEqualTo(method);
        assertThat(fieldEvaluation.selectedCandidate().projectedValue())
                .isEqualByComparingTo(new BigDecimal(projectedValue));
    }

    private BuildingRegisterRecord recap(String plat, String arch, String vlArea, String bcRat, String vlRat) {
        return new BuildingRegisterRecord(
                ROOT,
                null,
                1,
                "0",
                "02000",
                decimal(plat),
                decimal(arch),
                null,
                decimal(vlArea),
                decimal(bcRat),
                decimal(vlRat));
    }

    private BuildingRegisterRecord title(
            String key,
            String parent,
            String plat,
            String arch,
            String vlArea,
            String bcRat,
            String vlRat,
            String purpose) {
        return new BuildingRegisterRecord(
                key,
                parent,
                3,
                "0",
                purpose,
                decimal(plat),
                decimal(arch),
                null,
                decimal(vlArea),
                decimal(bcRat),
                decimal(vlRat));
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
