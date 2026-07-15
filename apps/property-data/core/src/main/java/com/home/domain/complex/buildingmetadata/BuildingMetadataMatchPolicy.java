package com.home.domain.complex.buildingmetadata;

import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class BuildingMetadataMatchPolicy {

    public MatchResult resolveOdc(String pnu, List<InternalCandidate> internal, List<SourceCandidate> source) {
        validatePnu(pnu);
        List<InternalCandidate> pnuInternal = pnuInternal(pnu, internal);
        MatchResult identity = identityResult(pnu, pnuInternal, source);
        if (identity != null) return identity;
        if (pnuInternal.isEmpty() || source == null || source.isEmpty()) return unavailable();
        if (pnuInternal.size() == 1 && source.size() == 1) {
            SourceCandidate candidate = source.get(0);
            if (candidate.names().stream()
                    .allMatch(name -> ComplexNameNormalizer.normalize(name).isEmpty())) {
                return ambiguous("ODC candidate name is empty");
            }
            if (bestScore(pnuInternal.get(0), candidate) == 0) {
                return conflict("ODC candidate name conflicts with the PNU complex");
            }
            return resolved(pnuInternal.get(0).complexId(), candidate, "ODC_EXACT_NAME");
        }
        return mutualUnique(pnuInternal, source, "ODC_EXACT_NAME");
    }

    public MatchResult resolveBuilding(String pnu, List<InternalCandidate> internal, List<SourceCandidate> source) {
        validatePnu(pnu);
        List<InternalCandidate> pnuInternal = pnuInternal(pnu, internal);
        MatchResult identity = identityResult(pnu, pnuInternal, source);
        if (identity != null) return identity;
        if (pnuInternal.isEmpty() || source == null || source.isEmpty()) return unavailable();
        if (pnuInternal.size() == 1 && source.size() == 1) {
            InternalCandidate target = pnuInternal.get(0);
            SourceCandidate candidate = source.get(0);
            String name = candidate.names().isEmpty()
                    ? ""
                    : ComplexNameNormalizer.normalize(candidate.names().get(0));
            if (!name.isEmpty() && bestScore(target, candidate) == 0) {
                return conflict("building register name conflicts with the PNU complex");
            }
            return resolved(
                    target.complexId(), candidate, name.isEmpty() ? "BLD_SINGLE_PNU_EMPTY_NAME" : "BLD_EXACT_NAME");
        }
        MatchResult result = mutualUnique(pnuInternal, source, "BLD_EXACT_NAME");
        if (result.status().isResolvedLike()) {
            InternalCandidate target = pnuInternal.stream()
                    .filter(item -> item.complexId() == result.complexId())
                    .findFirst()
                    .orElseThrow();
            if (auxiliaryConflict(target.values(), result.candidate().values())) {
                return ambiguous("building auxiliary values conflict");
            }
        }
        return result;
    }

    private MatchResult identityResult(String pnu, List<InternalCandidate> internal, List<SourceCandidate> source) {
        if (source == null) return null;
        for (SourceCandidate candidate : source) {
            if (candidate.identityComplexId() == null) continue;
            InternalCandidate match = internal.stream()
                    .filter(item -> item.complexId() == candidate.identityComplexId())
                    .findFirst()
                    .orElse(null);
            if (match == null || !pnu.equals(candidate.pnu())) {
                return ambiguous("approved source identity PNU conflicts");
            }
            return resolved(match.complexId(), candidate, "APPROVED_SOURCE_IDENTITY");
        }
        return null;
    }

    private MatchResult mutualUnique(List<InternalCandidate> internal, List<SourceCandidate> source, String path) {
        List<Pair> pairs = new ArrayList<>();
        for (SourceCandidate candidate : source) {
            List<Pair> ranked = internal.stream()
                    .map(target -> new Pair(target, candidate, bestScore(target, candidate)))
                    .filter(pair -> pair.score() > 0)
                    .sorted(Comparator.comparingInt(Pair::score).reversed())
                    .toList();
            if (ranked.isEmpty()) continue;
            if (ranked.size() > 1 && ranked.get(0).score() == ranked.get(1).score()) {
                return ambiguous("source name matches multiple PNU complexes");
            }
            pairs.add(ranked.get(0));
        }
        if (pairs.isEmpty()) return conflict("no exact name match inside PNU");
        List<Pair> mutual = pairs.stream()
                .filter(pair -> {
                    List<Pair> forTarget = pairs.stream()
                            .filter(other -> other.internal().complexId()
                                    == pair.internal().complexId())
                            .sorted(Comparator.comparingInt(Pair::score).reversed())
                            .toList();
                    return forTarget.size() == 1
                            || forTarget.get(0).score() > forTarget.get(1).score();
                })
                .toList();
        if (mutual.size() != 1) return ambiguous("name matching is not mutually unique");
        Pair selected = mutual.get(0);
        return resolved(selected.internal().complexId(), selected.source(), path);
    }

    private int bestScore(InternalCandidate internal, SourceCandidate source) {
        int best = 0;
        for (int externalIndex = 0; externalIndex < source.names().size(); externalIndex++) {
            String external = ComplexNameNormalizer.normalize(source.names().get(externalIndex));
            if (external.isEmpty()) continue;
            for (InternalName name : internal.names()) {
                if (external.equals(ComplexNameNormalizer.normalize(name.value()))) {
                    best = Math.max(best, 10_000 - externalIndex * 100 - name.priority());
                }
            }
        }
        return best;
    }

    private boolean auxiliaryConflict(BuildingMetadataValues current, BuildingMetadataValues candidate) {
        BuildingMetadataValues left = current == null ? BuildingMetadataValues.empty() : current.sanitized();
        BuildingMetadataValues right = candidate == null ? BuildingMetadataValues.empty() : candidate.sanitized();
        return different(left.dongCnt(), right.dongCnt())
                || different(left.unitCnt(), right.unitCnt())
                || different(left.useDate(), right.useDate());
    }

    private boolean different(Object left, Object right) {
        return left != null && right != null && !left.equals(right);
    }

    private List<InternalCandidate> pnuInternal(String pnu, List<InternalCandidate> internal) {
        return internal == null
                ? List.of()
                : internal.stream().filter(item -> pnu.equals(item.pnu())).toList();
    }

    private void validatePnu(String pnu) {
        if (pnu == null || !pnu.matches("\\d{19}")) throw new IllegalArgumentException("PNU must be 19 digits");
    }

    private MatchResult resolved(long id, SourceCandidate candidate, String path) {
        return new MatchResult(ComplexMetadataStatus.RESOLVED, id, path, null, null, candidate);
    }

    private MatchResult unavailable() {
        return new MatchResult(
                ComplexMetadataStatus.UNAVAILABLE,
                null,
                null,
                ComplexMetadataFailureKind.SOURCE_MISSING,
                "source candidate unavailable",
                null);
    }

    private MatchResult conflict(String reason) {
        return new MatchResult(
                ComplexMetadataStatus.AMBIGUOUS, null, null, ComplexMetadataFailureKind.AMBIGUOUS, reason, null);
    }

    private MatchResult ambiguous(String reason) {
        return new MatchResult(
                ComplexMetadataStatus.AMBIGUOUS, null, null, ComplexMetadataFailureKind.AMBIGUOUS, reason, null);
    }

    public record InternalName(String type, String value, int priority) {
        public InternalName {
            Objects.requireNonNull(type);
        }
    }

    public record InternalCandidate(
            long complexId, String pnu, List<InternalName> names, BuildingMetadataValues values) {
        public InternalCandidate {
            names = names == null ? List.of() : List.copyOf(names);
        }
    }

    public record SourceCandidate(
            String sourceKey, String pnu, List<String> names, BuildingMetadataValues values, Long identityComplexId) {
        public SourceCandidate {
            names = names == null ? List.of() : List.copyOf(names);
        }
    }

    public record MatchResult(
            ComplexMetadataStatus status,
            Long complexId,
            String matchPath,
            ComplexMetadataFailureKind failureKind,
            String failureReason,
            SourceCandidate candidate) {}

    private record Pair(InternalCandidate internal, SourceCandidate source, int score) {}
}
