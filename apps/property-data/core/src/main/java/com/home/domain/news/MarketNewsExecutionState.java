package com.home.domain.news;

public enum MarketNewsExecutionState {
    PLANNED("계획됨", "수집 실행이 아직 시작되지 않음"),
    RUNNING("실행 중", "수집 work unit을 처리 중"),
    COMPLETED("완료", "모든 필수 work unit이 정상 완료"),
    PARTIAL("부분 완료", "일부 work unit이 정상 완료되지 않음"),
    FAILED("실패", "수집 실행이 정상 결과를 만들지 못함");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsExecutionState(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean canTransitionTo(MarketNewsExecutionState target) {
        if (target == null || target == this || this == COMPLETED || this == PARTIAL || this == FAILED) {
            return false;
        }
        return (this == PLANNED && target == RUNNING)
                || (this == RUNNING && (target == COMPLETED || target == PARTIAL || target == FAILED));
    }
}
