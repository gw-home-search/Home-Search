package com.home.domain.complex.buildingmetadata;

import java.math.BigDecimal;

public final class BuildingMetadataProjectionPolicy {

    public ProjectionDecision decide(BuildingMetadataValues current, BuildingMetadataValues candidate) {
        BuildingMetadataValues safeCurrent = current == null ? BuildingMetadataValues.empty() : current.sanitized();
        BuildingMetadataValues safeCandidate =
                candidate == null ? BuildingMetadataValues.empty() : candidate.sanitized();
        if (conflict(safeCurrent.dongCnt(), safeCandidate.dongCnt())
                || conflict(safeCurrent.unitCnt(), safeCandidate.unitCnt())
                || conflict(safeCurrent.platArea(), safeCandidate.platArea())
                || conflict(safeCurrent.archArea(), safeCandidate.archArea())
                || conflict(safeCurrent.totArea(), safeCandidate.totArea())
                || conflict(safeCurrent.bcRat(), safeCandidate.bcRat())
                || conflict(safeCurrent.vlRat(), safeCandidate.vlRat())
                || conflict(safeCurrent.useDate(), safeCandidate.useDate())) {
            return new ProjectionDecision(safeCurrent, false);
        }
        BuildingMetadataValues merged = new BuildingMetadataValues(
                first(safeCurrent.dongCnt(), safeCandidate.dongCnt()),
                first(safeCurrent.unitCnt(), safeCandidate.unitCnt()),
                first(safeCurrent.platArea(), safeCandidate.platArea()),
                first(safeCurrent.archArea(), safeCandidate.archArea()),
                first(safeCurrent.totArea(), safeCandidate.totArea()),
                first(safeCurrent.bcRat(), safeCandidate.bcRat()),
                first(safeCurrent.vlRat(), safeCandidate.vlRat()),
                first(safeCurrent.useDate(), safeCandidate.useDate()));
        return new ProjectionDecision(merged, true);
    }

    private boolean conflict(Object left, Object right) {
        if (left == null || right == null) return false;
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) return a.compareTo(b) != 0;
        return !left.equals(right);
    }

    private <T> T first(T left, T right) {
        return left != null ? left : right;
    }

    public record ProjectionDecision(BuildingMetadataValues values, boolean apply) {}
}
