package com.home.domain.insight;

public enum RtmsCollectionMode {
    DAILY("일일 수집", "전국 일일 공개 거래 수집"),
    BACKFILL("과거 보강", "지정 과거 기간 보강 수집"),
    REPLAY("재처리", "보존된 근거의 재처리"),
    MAINTENANCE("유지보수", "운영 유지보수 수집");

    private final String titleKo;
    private final String descriptionKo;

    RtmsCollectionMode(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean qualifiesForDailyInsight() {
        return this == DAILY;
    }
}
