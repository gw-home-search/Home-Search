package com.home.domain.complex.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingRegisterHierarchyMatcherTest {
    private final BuildingRegisterHierarchyPolicy hierarchy = new BuildingRegisterHierarchyPolicy();
    private final BuildingRegisterComplexMatchPolicy matcher = new BuildingRegisterComplexMatchPolicy();

    @Test
    @DisplayName("건축물대장 계층과 단지 매칭을 검증한다")
    void marksRootIncompleteWhenExpectedBasicOverviewChildIsMissingFromTitles() {
        var result = hierarchy.resolve(List.of(
                record(BuildingRegisterEndpoint.RECAP_TITLE, "ROOT", null, 1, "1", "Sample", null),
                record(BuildingRegisterEndpoint.BASIC_OVERVIEW, "TITLE-1", "ROOT", 3, "1", "Sample", "101")));

        assertThat(result.status()).isEqualTo(BuildingRegisterHierarchyStatus.INCOMPLETE_HIERARCHY);
        assertThat(result.scopes()).isEmpty();
    }

    @Test
    @DisplayName("건축물대장 계층과 단지 매칭을 검증한다")
    void rejectsConflictingDuplicateManagementKey() {
        var result = hierarchy.resolve(List.of(
                record(BuildingRegisterEndpoint.RECAP_TITLE, "ROOT", null, 1, "1", "Sample", null),
                record(BuildingRegisterEndpoint.TITLE, "TITLE-1", "ROOT", 3, "1", "Sample", "101"),
                record(BuildingRegisterEndpoint.TITLE, "TITLE-1", "ROOT", 3, "1", "Other", "101")));

        assertThat(result.status()).isEqualTo(BuildingRegisterHierarchyStatus.SOURCE_CONFLICT);
    }

    @Test
    @DisplayName("건축물대장 계층과 단지 매칭을 검증한다")
    void rejectsMultipleNewGenerationRecaps() {
        var result = hierarchy.resolve(List.of(
                record(BuildingRegisterEndpoint.RECAP_TITLE, "ROOT-1", null, 1, "1", "A", null),
                record(BuildingRegisterEndpoint.RECAP_TITLE, "ROOT-2", null, 1, "1", "B", null)));

        assertThat(result.status()).isEqualTo(BuildingRegisterHierarchyStatus.AMBIGUOUS_GENERATION);
    }

    @Test
    @DisplayName("건축물대장 계층과 단지 매칭을 검증한다")
    void permitsSingleStandaloneTitleOnlyForSinglePnuComplex() {
        var source = hierarchy.resolve(
                List.of(record(BuildingRegisterEndpoint.TITLE, "TITLE-1", null, 3, "1", "Sample", "101")));

        var resolved = matcher.match(List.of(target(1, "TITLE-1", Set.of("Sample"), Set.of("101"))), source.scopes());

        assertThat(source.status()).isEqualTo(BuildingRegisterHierarchyStatus.RESOLVED);
        assertThat(resolved).singleElement().satisfies(match -> {
            assertThat(match.path()).isEqualTo(BuildingRegisterMatchPath.EXISTING_KEY);
            assertThat(match.scope()).isEqualTo(BuildingRatioScope.STANDALONE_TITLE);
            assertThat(match.projectable()).isTrue();
        });
    }

    @Test
    @DisplayName("건축물대장 계층과 단지 매칭을 검증한다")
    void appliesStructuralMatchPriorityWithoutSimilarity() {
        List<BuildingRegisterSourceScope> roots =
                List.of(scope("ROOT-A", "Alpha", Set.of("101", "102")), scope("ROOT-B", "Beta", Set.of("201", "202")));
        List<BuildingRegisterComplexTarget> targets = List.of(
                target(1, "ROOT-A", Set.of("Wrong name"), Set.of()),
                target(2, null, Set.of("Beta"), Set.of("unrelated")));

        var matches = matcher.match(targets, roots);

        assertThat(matches)
                .extracting(BuildingRegisterComplexMatch::path)
                .containsExactly(BuildingRegisterMatchPath.EXISTING_KEY, BuildingRegisterMatchPath.EXACT_NAME);
        assertThat(matches)
                .extracting(BuildingRegisterComplexMatch::rootManagementKey)
                .containsExactly("ROOT-A", "ROOT-B");
    }

    @Test
    @DisplayName("건축물대장 계층과 단지 매칭을 검증한다")
    void usesExactDongSetOnlyWhenMutuallyUnique() {
        List<BuildingRegisterSourceScope> roots = List.of(
                scope("ROOT-A", "Unknown A", Set.of("101", "102")), scope("ROOT-B", "Unknown B", Set.of("201", "202")));
        List<BuildingRegisterComplexTarget> targets = List.of(
                target(1, null, Set.of("No name A"), Set.of("101", "102")),
                target(2, null, Set.of("No name B"), Set.of("201", "202")));

        assertThat(matcher.match(targets, roots))
                .extracting(BuildingRegisterComplexMatch::path)
                .containsExactly(BuildingRegisterMatchPath.EXACT_DONG_SET, BuildingRegisterMatchPath.EXACT_DONG_SET);
    }

    @Test
    @DisplayName("건축물대장 계층과 단지 매칭을 검증한다")
    void marksOneRecapSharedByMultipleComplexesAsNonProjectable() {
        List<BuildingRegisterComplexTarget> targets =
                List.of(target(1, null, Set.of("A"), Set.of("101")), target(2, null, Set.of("B"), Set.of("102")));

        var matches = matcher.match(targets, List.of(scope("ROOT", "Shared", Set.of("101", "102"))));

        assertThat(matches).allSatisfy(match -> {
            assertThat(match.scope()).isEqualTo(BuildingRatioScope.SHARED_RECAP);
            assertThat(match.projectable()).isFalse();
            assertThat(match.status()).isEqualTo(BuildingRegisterMatchStatus.RESOLVED);
        });
    }

    @Test
    @DisplayName("건축물대장 계층과 단지 매칭을 검증한다")
    void leavesPartialSubstringNameUnmatched() {
        var matches = matcher.match(
                List.of(target(1, null, Set.of("Sample Apartment"), Set.of())),
                List.of(scope("ROOT-A", "Sample", Set.of()), scope("ROOT-B", "Other", Set.of())));

        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.status()).isEqualTo(BuildingRegisterMatchStatus.AMBIGUOUS);
            assertThat(match.rootManagementKey()).isNull();
        });
    }

    private BuildingRegisterHierarchyRecord record(
            BuildingRegisterEndpoint endpoint,
            String key,
            String parent,
            int kind,
            String generation,
            String buildingName,
            String dongName) {
        return new BuildingRegisterHierarchyRecord(endpoint, key, parent, kind, generation, buildingName, dongName);
    }

    private BuildingRegisterSourceScope scope(String key, String name, Set<String> dongs) {
        return new BuildingRegisterSourceScope(key, BuildingRatioScope.UNIQUE_ROOT, name, dongs, Set.of());
    }

    private BuildingRegisterComplexTarget target(
            long id, String existingKey, Set<String> names, Set<String> tradeDongNames) {
        return new BuildingRegisterComplexTarget(id, existingKey, names, tradeDongNames, Set.of());
    }
}
