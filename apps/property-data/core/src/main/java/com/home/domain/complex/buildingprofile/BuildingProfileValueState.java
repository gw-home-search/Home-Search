package com.home.domain.complex.buildingprofile;

public enum BuildingProfileValueState {
    ABSENT("키 없음", "응답 item에 key가 존재하지 않음"),
    NULL("null", "응답 key가 JSON null임"),
    BLANK("공백", "문자열이 비어 있거나 공백뿐임"),
    ZERO("0", "숫자 0을 zero policy와 함께 보존"),
    POSITIVE("양수", "0보다 큰 유효 숫자"),
    VALID("유효", "유효한 문자열·날짜·논리 또는 음수 허용 값"),
    INVALID("파싱 오류", "원천 값이 선언된 type으로 해석되지 않음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileValueState(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean hasTypedValue() {
        return this == ZERO || this == POSITIVE || this == VALID;
    }
}
