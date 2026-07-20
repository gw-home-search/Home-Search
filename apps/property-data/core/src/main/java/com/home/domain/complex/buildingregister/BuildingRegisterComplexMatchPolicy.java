package com.home.domain.complex.buildingregister;

import com.home.domain.complex.buildingmetadata.ComplexNameNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class BuildingRegisterComplexMatchPolicy {
    public List<BuildingRegisterComplexMatch> match(
            List<BuildingRegisterComplexTarget> sourceTargets, List<BuildingRegisterSourceScope> sourceScopes) {
        List<BuildingRegisterComplexTarget> targets = sourceTargets == null ? List.of() : List.copyOf(sourceTargets);
        List<BuildingRegisterSourceScope> scopes = sourceScopes == null ? List.of() : List.copyOf(sourceScopes);
        if (scopes.size() == 1 && targets.size() > 1) return shared(targets, scopes.getFirst());

        Map<Long, BuildingRegisterComplexMatch> resolved = new LinkedHashMap<>();
        Set<String> claimedRoots = new LinkedHashSet<>();
        for (BuildingRegisterComplexTarget target : targets) {
            if (target.existingManagementKey() == null) continue;
            scopes.stream()
                    .filter(scope -> scope.rootManagementKey().equals(target.existingManagementKey()))
                    .findFirst()
                    .ifPresent(scope ->
                            resolve(resolved, claimedRoots, target, scope, BuildingRegisterMatchPath.EXISTING_KEY));
        }
        if (targets.size() == 1 && scopes.size() == 1 && resolved.isEmpty()) {
            resolve(
                    resolved,
                    claimedRoots,
                    targets.getFirst(),
                    scopes.getFirst(),
                    BuildingRegisterMatchPath.UNIQUE_PNU);
        }
        mutualUnique(
                targets,
                scopes,
                resolved,
                claimedRoots,
                target -> normalized(target.names()),
                scope -> normalized(Set.of(scope.buildingName() == null ? "" : scope.buildingName())),
                BuildingRegisterMatchPath.EXACT_NAME);
        mutualUnique(
                targets,
                scopes,
                resolved,
                claimedRoots,
                target -> normalized(target.tradeDongNames()),
                scope -> normalized(scope.dongNames()),
                BuildingRegisterMatchPath.EXACT_DONG_SET);
        mutualUnique(
                targets,
                scopes,
                resolved,
                claimedRoots,
                target -> normalized(target.footprintDongNames()),
                scope -> normalized(scope.dongNames()),
                BuildingRegisterMatchPath.FOOTPRINT_EVIDENCE);

        List<BuildingRegisterComplexMatch> matches = new ArrayList<>();
        for (BuildingRegisterComplexTarget target : targets) {
            matches.add(resolved.getOrDefault(
                    target.complexId(),
                    new BuildingRegisterComplexMatch(
                            target.complexId(),
                            null,
                            BuildingRatioScope.UNIQUE_ROOT,
                            scopes.isEmpty()
                                    ? BuildingRegisterMatchStatus.SOURCE_MISSING
                                    : BuildingRegisterMatchStatus.AMBIGUOUS,
                            null,
                            false,
                            scopes.isEmpty() ? "source scope is missing" : "no mutually unique exact match")));
        }
        return List.copyOf(matches);
    }

    private void mutualUnique(
            List<BuildingRegisterComplexTarget> targets,
            List<BuildingRegisterSourceScope> scopes,
            Map<Long, BuildingRegisterComplexMatch> resolved,
            Set<String> claimedRoots,
            Function<BuildingRegisterComplexTarget, Set<String>> targetEvidence,
            Function<BuildingRegisterSourceScope, Set<String>> scopeEvidence,
            BuildingRegisterMatchPath path) {
        Map<Long, List<BuildingRegisterSourceScope>> candidates = new LinkedHashMap<>();
        for (BuildingRegisterComplexTarget target : targets) {
            if (resolved.containsKey(target.complexId())) continue;
            Set<String> evidence = targetEvidence.apply(target);
            if (evidence.isEmpty()) continue;
            List<BuildingRegisterSourceScope> found = scopes.stream()
                    .filter(scope -> !claimedRoots.contains(scope.rootManagementKey()))
                    .filter(scope -> exactEvidence(evidence, scopeEvidence.apply(scope)))
                    .toList();
            candidates.put(target.complexId(), found);
        }
        for (BuildingRegisterComplexTarget target : targets) {
            List<BuildingRegisterSourceScope> found = candidates.getOrDefault(target.complexId(), List.of());
            if (found.size() != 1) continue;
            BuildingRegisterSourceScope scope = found.getFirst();
            long owners = candidates.values().stream()
                    .filter(list -> list.size() == 1
                            && list.getFirst().rootManagementKey().equals(scope.rootManagementKey()))
                    .count();
            if (owners == 1) resolve(resolved, claimedRoots, target, scope, path);
        }
    }

    private boolean exactEvidence(Set<String> target, Set<String> source) {
        return !target.isEmpty() && target.equals(source);
    }

    private Set<String> normalized(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = ComplexNameNormalizer.normalize(value);
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    private void resolve(
            Map<Long, BuildingRegisterComplexMatch> resolved,
            Set<String> claimedRoots,
            BuildingRegisterComplexTarget target,
            BuildingRegisterSourceScope scope,
            BuildingRegisterMatchPath path) {
        if (claimedRoots.contains(scope.rootManagementKey())) return;
        resolved.put(
                target.complexId(),
                new BuildingRegisterComplexMatch(
                        target.complexId(),
                        scope.rootManagementKey(),
                        scope.scope(),
                        BuildingRegisterMatchStatus.RESOLVED,
                        path,
                        scope.scope().projectable(),
                        null));
        claimedRoots.add(scope.rootManagementKey());
    }

    private List<BuildingRegisterComplexMatch> shared(
            List<BuildingRegisterComplexTarget> targets, BuildingRegisterSourceScope scope) {
        return targets.stream()
                .map(target -> new BuildingRegisterComplexMatch(
                        target.complexId(),
                        scope.rootManagementKey(),
                        BuildingRatioScope.SHARED_RECAP,
                        BuildingRegisterMatchStatus.RESOLVED,
                        null,
                        false,
                        "one recap root is shared by multiple complexes"))
                .toList();
    }
}
