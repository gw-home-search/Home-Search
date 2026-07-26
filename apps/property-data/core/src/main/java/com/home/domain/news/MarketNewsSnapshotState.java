package com.home.domain.news;

public enum MarketNewsSnapshotState {
    BUILDING("생성 중", "공개 전 원자 목록을 작성 중"),
    PUBLISHED("발행됨", "공개 조회가 사용하는 정상 목록"),
    REJECTED("거부됨", "자동 품질 검사를 통과하지 못한 목록"),
    SUPERSEDED("대체됨", "더 최신 정상 목록으로 교체된 발행"),
    WITHDRAWN("회수됨", "사후 품질 검토로 공개에서 회수된 발행");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsSnapshotState(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean canTransitionTo(MarketNewsSnapshotState target) {
        if (target == null || target == this || this == REJECTED || this == SUPERSEDED || this == WITHDRAWN) {
            return false;
        }
        return (this == BUILDING && (target == PUBLISHED || target == REJECTED))
                || (this == PUBLISHED && (target == SUPERSEDED || target == WITHDRAWN));
    }
}
