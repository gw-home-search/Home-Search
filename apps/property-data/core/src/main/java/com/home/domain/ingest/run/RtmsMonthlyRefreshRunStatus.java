package com.home.domain.ingest.run;

public enum RtmsMonthlyRefreshRunStatus {
    COMPLETED("COMPLETED", false, "수집 완료", "월별 RTMS 수집과 저장이 모두 완료된 상태입니다."),
    PARTIAL("PARTIAL", true, "부분 완료", "일부 RTMS 항목이 실패했지만 처리 가능한 항목은 저장된 상태입니다."),
    FAILED("FAILED", true, "수집 실패", "월별 RTMS 수집을 완료하지 못한 상태입니다.");

    private final String storedValue;
    private final boolean failure;
    private final String titleKo;
    private final String descriptionKo;

    RtmsMonthlyRefreshRunStatus(String storedValue, boolean failure, String titleKo, String descriptionKo) {
        this.storedValue = storedValue;
        this.failure = failure;
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String storedValue() {
        return storedValue;
    }

    public String failureReason(String failureReason) {
        return failure ? failureReason : null;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }
}
