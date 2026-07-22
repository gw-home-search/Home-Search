package com.home.domain.insight;

public enum RtmsCollectionWorkUnitState {
    PLANNED("계획됨", "실행 전 work unit"),
    RUNNING("실행 중", "현재 수집 중인 work unit"),
    COMPLETED("완료", "수집이 성공한 work unit"),
    PARTIAL("부분 완료", "일부 page 수집 후 실패한 work unit"),
    FAILED("실패", "수집 결과를 만들지 못한 work unit");

    private final String titleKo;
    private final String descriptionKo;

    RtmsCollectionWorkUnitState(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean terminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED;
    }

    public boolean successful() {
        return this == COMPLETED;
    }
}
