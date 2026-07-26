package com.home.domain.news;

public enum MarketNewsWorkUnitState {
    PLANNED("계획됨", "검색 work unit이 아직 시작되지 않음"),
    RUNNING("실행 중", "검색 page를 수집 중"),
    COMPLETED("완료", "overlap cutoff까지 정상 수집"),
    TRUNCATED("잘림", "provider start 제한 전에 cutoff에 도달하지 못함"),
    FAILED("실패", "provider 또는 저장 오류로 완료하지 못함"),
    SKIPPED_BUDGET("예산 제외", "일일 호출 예산 소진으로 실행하지 않음");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsWorkUnitState(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean isSuccessful() {
        return this == COMPLETED;
    }

    public boolean canTransitionTo(MarketNewsWorkUnitState target) {
        if (target == null || target == this || this.ordinal() >= COMPLETED.ordinal()) {
            return false;
        }
        return (this == PLANNED && (target == RUNNING || target == SKIPPED_BUDGET))
                || (this == RUNNING
                        && (target == COMPLETED
                                || target == TRUNCATED
                                || target == FAILED
                                || target == SKIPPED_BUDGET));
    }
}
