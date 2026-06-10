package com.home.domain.news;

/**
 * 수집 run에서 keyword가 발견한 article이 observation 저장에서 신규였는지 중복이었는지 남기는 provenance 상태입니다.
 */
public enum NewsCollectionArticleDisposition {

	OBSERVED("신규 관측", "해당 keyword 실행에서 새 news_article_observation row로 저장된 article"),
	DUPLICATE("중복 관측", "이미 저장된 article이지만 해당 keyword에서 다시 발견되어 provenance만 추가된 article");

	private final String titleKo;
	private final String descriptionKo;

	NewsCollectionArticleDisposition(String titleKo, String descriptionKo) {
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
