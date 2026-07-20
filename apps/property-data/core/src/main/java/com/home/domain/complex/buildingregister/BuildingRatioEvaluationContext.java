package com.home.domain.complex.buildingregister;

import java.util.List;
import java.util.Set;

public record BuildingRatioEvaluationContext(
        BuildingRegisterCollectionStrategy strategy,
        BuildingRatioScope scope,
        BuildingRegisterRecord recap,
        List<BuildingRegisterRecord> titles,
        Set<String> expectedTitleKeys,
        boolean hierarchyComplete) {
    public BuildingRatioEvaluationContext {
        if (strategy == null) throw new IllegalArgumentException("collection strategy is required");
        if (scope == null) throw new IllegalArgumentException("ratio scope is required");
        titles = titles == null ? List.of() : List.copyOf(titles);
        expectedTitleKeys = expectedTitleKeys == null ? Set.of() : Set.copyOf(expectedTitleKeys);
    }

    public static BuildingRatioEvaluationContext uniqueRoot(
            BuildingRegisterCollectionStrategy strategy,
            BuildingRegisterRecord recap,
            List<BuildingRegisterRecord> titles,
            Set<String> expectedTitleKeys,
            boolean hierarchyComplete) {
        if (recap == null) throw new IllegalArgumentException("unique root requires a recap record");
        return new BuildingRatioEvaluationContext(
                strategy, BuildingRatioScope.UNIQUE_ROOT, recap, titles, expectedTitleKeys, hierarchyComplete);
    }

    public static BuildingRatioEvaluationContext sharedRoot(
            BuildingRegisterCollectionStrategy strategy,
            BuildingRegisterRecord recap,
            List<BuildingRegisterRecord> titles,
            Set<String> expectedTitleKeys,
            boolean hierarchyComplete) {
        if (recap == null) throw new IllegalArgumentException("shared root requires a recap record");
        return new BuildingRatioEvaluationContext(
                strategy, BuildingRatioScope.SHARED_RECAP, recap, titles, expectedTitleKeys, hierarchyComplete);
    }

    public static BuildingRatioEvaluationContext standalone(BuildingRegisterRecord title) {
        if (title == null) throw new IllegalArgumentException("standalone title is required");
        return new BuildingRatioEvaluationContext(
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                BuildingRatioScope.STANDALONE_TITLE,
                null,
                List.of(title),
                Set.of(title.managementKey()),
                true);
    }
}
