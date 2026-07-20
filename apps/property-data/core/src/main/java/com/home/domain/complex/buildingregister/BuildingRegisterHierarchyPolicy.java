package com.home.domain.complex.buildingregister;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BuildingRegisterHierarchyPolicy {
    public BuildingRegisterHierarchyResult resolve(List<BuildingRegisterHierarchyRecord> sourceRecords) {
        List<BuildingRegisterHierarchyRecord> records = sourceRecords == null ? List.of() : List.copyOf(sourceRecords);
        Map<String, BuildingRegisterHierarchyRecord> unique = new LinkedHashMap<>();
        for (BuildingRegisterHierarchyRecord record : records) {
            String identity = record.endpoint() + ":" + record.managementKey();
            BuildingRegisterHierarchyRecord previous = unique.putIfAbsent(identity, record);
            if (previous != null && !previous.equals(record)) {
                return result(BuildingRegisterHierarchyStatus.SOURCE_CONFLICT, "conflicting duplicate management key");
            }
        }
        List<BuildingRegisterHierarchyRecord> distinct = new ArrayList<>(unique.values());
        List<BuildingRegisterHierarchyRecord> roots = distinct.stream()
                .filter(BuildingRegisterHierarchyRecord::isRoot)
                .toList();
        long newRoots = roots.stream()
                .filter(record -> "1".equals(record.newOldRegisterCode()))
                .count();
        if (newRoots > 1) {
            return result(BuildingRegisterHierarchyStatus.AMBIGUOUS_GENERATION, "multiple new-generation recap roots");
        }
        if (newRoots == 1) {
            roots = roots.stream()
                    .filter(record -> "1".equals(record.newOldRegisterCode()))
                    .toList();
        }
        if (roots.isEmpty()) return standalone(distinct);

        List<BuildingRegisterSourceScope> scopes = new ArrayList<>();
        Set<String> actualTitleKeys = distinct.stream()
                .filter(BuildingRegisterHierarchyRecord::isTitleLike)
                .map(BuildingRegisterHierarchyRecord::managementKey)
                .collect(java.util.stream.Collectors.toSet());
        for (BuildingRegisterHierarchyRecord root : roots) {
            Set<String> expected = distinct.stream()
                    .filter(BuildingRegisterHierarchyRecord::isExpectedChild)
                    .filter(record -> root.managementKey().equals(record.parentManagementKey()))
                    .map(BuildingRegisterHierarchyRecord::managementKey)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<BuildingRegisterHierarchyRecord> titles = distinct.stream()
                    .filter(BuildingRegisterHierarchyRecord::isTitleLike)
                    .filter(record -> expected.contains(record.managementKey()))
                    .toList();
            if (!actualTitleKeys.containsAll(expected)) {
                return result(
                        BuildingRegisterHierarchyStatus.INCOMPLETE_HIERARCHY,
                        "expected title management key is missing");
            }
            Set<String> dongs = titles.stream()
                    .map(BuildingRegisterHierarchyRecord::dongName)
                    .filter(name -> name != null)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            boolean complete = !expected.isEmpty() || titles.isEmpty();
            scopes.add(new BuildingRegisterSourceScope(
                    root.managementKey(),
                    BuildingRatioScope.UNIQUE_ROOT,
                    root.buildingName(),
                    dongs,
                    expected,
                    complete));
        }
        return new BuildingRegisterHierarchyResult(BuildingRegisterHierarchyStatus.RESOLVED, scopes, null);
    }

    private BuildingRegisterHierarchyResult standalone(List<BuildingRegisterHierarchyRecord> records) {
        List<BuildingRegisterHierarchyRecord> titles = records.stream()
                .filter(BuildingRegisterHierarchyRecord::isTitleLike)
                .toList();
        if (titles.size() != 1 || records.size() != 1) {
            return result(BuildingRegisterHierarchyStatus.SOURCE_MISSING, "standalone title is not unique");
        }
        BuildingRegisterHierarchyRecord title = titles.getFirst();
        Set<String> dongs = title.dongName() == null ? Set.of() : Set.of(title.dongName());
        var scope = new BuildingRegisterSourceScope(
                title.managementKey(),
                BuildingRatioScope.STANDALONE_TITLE,
                title.buildingName(),
                dongs,
                Set.of(title.managementKey()),
                true);
        return new BuildingRegisterHierarchyResult(BuildingRegisterHierarchyStatus.RESOLVED, List.of(scope), null);
    }

    private BuildingRegisterHierarchyResult result(BuildingRegisterHierarchyStatus status, String reason) {
        return new BuildingRegisterHierarchyResult(status, List.of(), reason);
    }
}
