package com.home.domain.complex.buildingprofile;

public enum BuildingProfileCodeComparisonStatus {
    CODE_TRANSITION_EQUIVALENT("코드 전환 동등", "구·신 PNU가 같은 관리번호 집합을 반환"),
    OLD_ONLY_SUCCESS("기존만 성공", "기존 PNU만 record를 반환"),
    NEW_ONLY_SUCCESS("전환만 성공", "전환 candidate PNU만 record를 반환"),
    BOTH_DIFFERENT("양쪽 상이", "양쪽 모두 성공했으나 관리번호 집합이 다름"),
    BOTH_EMPTY("양쪽 empty", "양쪽 모두 정상 empty"),
    NOT_COMPARABLE_PROVIDER_FAILURE("provider 실패 비교 제외", "한쪽 이상 provider/parse 실패라 코드 불일치로 판정하지 않음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileCodeComparisonStatus(String titleKo, String descriptionKo) {
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
