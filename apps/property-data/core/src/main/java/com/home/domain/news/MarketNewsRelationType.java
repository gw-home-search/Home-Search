package com.home.domain.news;

public enum MarketNewsRelationType {
    NATIONWIDE("전국", "전국 카테고리 기사로 확인"),
    SAME_SIDO("같은 시도", "시도 근거를 기사에서 확인"),
    DIRECT_COMPLEX("단지 직접 언급", "단지명과 필요한 상위 지역을 기사에서 직접 확인"),
    SAME_DONG("같은 동", "시군구와 법정동을 기사에서 함께 확인"),
    SAME_SIGUNGU("같은 시군구", "시도와 시군구를 기사에서 함께 확인");

    private final String titleKo;
    private final String descriptionKo;

    MarketNewsRelationType(String titleKo, String descriptionKo) {
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
