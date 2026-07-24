package com.home.domain.complex.buildingprofile;

public enum BuildingProfileAssignmentStatus {
    RESOLVED("배정 완료", "단일 root/scope에 충돌 없이 배정", true, true),
    SHARED_SCOPE("공유 scope", "profile evidence는 보존하지만 complex projection에는 사용하지 않음", true, false),
    INCOMPLETE_HIERARCHY("불완전 계층", "기대 title 집합이 불완전", true, false),
    SOURCE_CONFLICT("원천 충돌", "동일 관리번호의 내용 또는 parent가 충돌", true, false),
    AMBIGUOUS_GENERATION("신구대장 불명확", "단일 신대장을 고를 수 없음", true, false),
    ORPHAN("orphan", "선택 root에 속하지 않는 record", true, false),
    SOURCE_MISSING("원천 누락", "배정할 source record가 없음", false, false);

    private final String titleKo;
    private final String descriptionKo;
    private final boolean retainsEvidence;
    private final boolean projectable;

    BuildingProfileAssignmentStatus(
            String titleKo, String descriptionKo, boolean retainsEvidence, boolean projectable) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
        this.retainsEvidence = retainsEvidence;
        this.projectable = projectable;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean retainsEvidence() {
        return retainsEvidence;
    }

    public boolean projectable() {
        return projectable;
    }
}
