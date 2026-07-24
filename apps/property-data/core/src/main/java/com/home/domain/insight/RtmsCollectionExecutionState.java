package com.home.domain.insight;

public enum RtmsCollectionExecutionState {
    PLANNED("계획됨", "work unit이 기록되고 실행 전인 상태"),
    RUNNING("실행 중", "하나 이상의 work unit을 실행 중인 상태"),
    COMPLETED("완료", "모든 work unit이 성공한 상태"),
    PARTIAL("부분 완료", "일부 work unit이 실패하거나 부분 완료된 상태"),
    FAILED("실패", "실행 자체가 완료되지 못한 상태");

    private final String titleKo;
    private final String descriptionKo;

    RtmsCollectionExecutionState(String titleKo, String descriptionKo) {
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
}
