package com.home.domain.ingest.source;

public enum RtmsSourceDateQuality {
    VALID("유효", "원천 날짜가 yy.MM.dd 형식으로 엄격하게 파싱됨"),
    MISSING("누락", "원천 날짜 값이 없음"),
    INVALID("형식 오류", "원천 날짜 값이 yy.MM.dd 형식 또는 실제 달력 날짜가 아님");

    private final String titleKo;
    private final String descriptionKo;

    RtmsSourceDateQuality(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }
}
