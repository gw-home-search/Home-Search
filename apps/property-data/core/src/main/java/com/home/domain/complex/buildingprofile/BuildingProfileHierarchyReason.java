package com.home.domain.complex.buildingprofile;

public enum BuildingProfileHierarchyReason {
    MULTIPLE_COMPLEXES("복수 단지", "동일 PNU에 둘 이상의 complex가 존재"),
    MULTIPLE_RECAP_ROOTS("복수 총괄 root", "동일 PNU에 둘 이상의 recap root가 존재"),
    TITLES_WITHOUT_RECAP("총괄 없는 복수 표제부", "recap이 없고 title이 둘 이상 존재"),
    MISSING_PARENT("상위 관리번호 누락", "title 관리번호의 parent가 없음"),
    PARENT_CONFLICT("상위 관리번호 충돌", "동일 title의 parent 관계가 충돌"),
    AMBIGUOUS_GENERATION("신구대장 불명확", "명확한 단일 신대장을 선택할 수 없음"),
    UNASSIGNABLE_TITLE("표제부 배정 불가", "title을 단일 root에 배정할 수 없음");

    private final String titleKo;
    private final String descriptionKo;

    BuildingProfileHierarchyReason(String titleKo, String descriptionKo) {
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
