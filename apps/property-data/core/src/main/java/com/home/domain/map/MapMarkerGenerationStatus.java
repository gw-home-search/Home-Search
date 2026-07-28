package com.home.domain.map;

public enum MapMarkerGenerationStatus {
    BUILDING("구축 중", "원본 watermark를 기준으로 projection을 별도 구축하는 상태"),
    VALIDATED("검증 완료", "행 수와 canonical hash 검증을 통과해 활성화할 수 있는 상태"),
    ACTIVE("활성", "공개 map 요청이 읽는 현재 generation 상태"),
    RETIRED("이전 세대", "pointer 전환 후 즉시 rollback을 위해 보존하는 상태"),
    FAILED("구축 실패", "기존 active generation을 유지한 채 새 구축이 중단된 상태");

    private final String titleKo;
    private final String descriptionKo;

    MapMarkerGenerationStatus(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean canActivate() {
        return this == VALIDATED || this == RETIRED;
    }

    public boolean canFail() {
        return this == BUILDING || this == VALIDATED;
    }

    public String storedValue() {
        return name();
    }
}
