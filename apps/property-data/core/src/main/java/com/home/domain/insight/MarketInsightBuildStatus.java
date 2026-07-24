package com.home.domain.insight;

public enum MarketInsightBuildStatus {
    BUILDING("생성 중", "snapshot과 item을 원자 발행 전에 작성 중"),
    PUBLISHED("발행됨", "공개 조회가 사용할 수 있는 snapshot"),
    REJECTED("거부됨", "coverage 또는 생성 자격을 충족하지 못한 근거"),
    SUPERSEDED("대체됨", "같은 기간의 더 최신 정상 실행 snapshot으로 원자 대체됨");

    private final String titleKo;
    private final String descriptionKo;

    MarketInsightBuildStatus(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean canTransitionTo(MarketInsightBuildStatus target) {
        if (target == null || this == target || this == REJECTED || this == SUPERSEDED) {
            return false;
        }
        return (this == BUILDING && (target == PUBLISHED || target == REJECTED))
                || (this == PUBLISHED && target == SUPERSEDED);
    }
}
