package com.home.domain.complex.buildingregister;

public enum BuildingRegisterRawPageStatus {
    RECEIVED("수신 완료", "응답 원문이 저장됐지만 아직 해석되지 않음"),
    PARSED("해석 완료", "응답 원문과 정규화 레코드가 함께 보존됨"),
    EMPTY("정상 빈 응답", "provider가 정상 응답했지만 레코드가 없음"),
    PROVIDER_FAILED("provider 실패", "HTTP 또는 provider 상태가 실패임"),
    PARSE_FAILED("해석 실패", "원문을 보존했으며 외부 재호출 없이 재처리할 수 있음"),
    OVERSIZED("크기 초과", "응답이 원문 저장 한도를 초과함");

    private final String titleKo;
    private final String descriptionKo;

    BuildingRegisterRawPageStatus(String titleKo, String descriptionKo) {
        this.titleKo = titleKo;
        this.descriptionKo = descriptionKo;
    }

    public String titleKo() {
        return titleKo;
    }

    public String descriptionKo() {
        return descriptionKo;
    }

    public boolean isFinalized() {
        return this != RECEIVED;
    }

    public boolean canTransitionTo(BuildingRegisterRawPageStatus next) {
        if (next == null || next == RECEIVED) {
            return false;
        }
        if (this == RECEIVED) {
            return true;
        }
        return this == PARSE_FAILED && (next == PARSED || next == EMPTY || next == PARSE_FAILED);
    }
}
