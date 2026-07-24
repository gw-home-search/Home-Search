package com.home.domain.complex.buildingprofile;

import com.home.domain.complex.buildingregister.BuildingRegisterHierarchyRecord;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record BuildingProfileHierarchyFacts(
        int complexCount,
        int recapRootCount,
        int titleCount,
        boolean parentComplete,
        boolean parentConflict,
        boolean ambiguousGeneration,
        boolean unassignableTitle) {
    public BuildingProfileHierarchyFacts {
        if (complexCount < 1 || recapRootCount < 0 || titleCount < 0) {
            throw new IllegalArgumentException("invalid hierarchy cardinality");
        }
    }

    public static BuildingProfileHierarchyFacts from(
            int complexCount, List<BuildingRegisterHierarchyRecord> sourceRecords) {
        List<BuildingRegisterHierarchyRecord> records = sourceRecords == null ? List.of() : List.copyOf(sourceRecords);
        List<BuildingRegisterHierarchyRecord> roots =
                records.stream().filter(BuildingRegisterHierarchyRecord::isRoot).toList();
        List<BuildingRegisterHierarchyRecord> titles = records.stream()
                .filter(BuildingRegisterHierarchyRecord::isTitleLike)
                .toList();
        Set<String> rootKeys = roots.stream()
                .map(BuildingRegisterHierarchyRecord::managementKey)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> titleKeys = titles.stream()
                .map(BuildingRegisterHierarchyRecord::managementKey)
                .collect(java.util.stream.Collectors.toSet());
        boolean parentComplete = titles.stream()
                .allMatch(
                        title -> title.parentManagementKey() != null && rootKeys.contains(title.parentManagementKey()));
        Map<String, Set<String>> parentsByTitle = new HashMap<>();
        for (BuildingRegisterHierarchyRecord title : titles) {
            parentsByTitle
                    .computeIfAbsent(title.managementKey(), ignored -> new HashSet<>())
                    .add(title.parentManagementKey());
        }
        boolean parentConflict = parentsByTitle.values().stream().anyMatch(parents -> parents.size() > 1);
        Map<String, Set<String>> generationsByRoot = new HashMap<>();
        for (BuildingRegisterHierarchyRecord root : roots) {
            generationsByRoot
                    .computeIfAbsent(root.managementKey(), ignored -> new HashSet<>())
                    .add(root.newOldRegisterCode());
        }
        boolean conflictingGeneration =
                generationsByRoot.values().stream().anyMatch(generations -> generations.size() > 1);
        long newRootCount = roots.stream()
                .filter(root -> "1".equals(root.newOldRegisterCode()))
                .map(BuildingRegisterHierarchyRecord::managementKey)
                .distinct()
                .count();
        boolean ambiguousGeneration = conflictingGeneration || (rootKeys.size() > 1 && newRootCount != 1);
        boolean unassignableTitle = titles.stream()
                .anyMatch(title ->
                        title.parentManagementKey() == null || !rootKeys.contains(title.parentManagementKey()));
        return new BuildingProfileHierarchyFacts(
                complexCount,
                rootKeys.size(),
                titleKeys.size(),
                parentComplete,
                parentConflict,
                ambiguousGeneration,
                unassignableTitle);
    }
}
