package com.home.domain.news;

public enum NewsDiscoveryMethod {

	PROVIDER_API("Provider API", "공식 provider API로 관측한 기사입니다."),
	OPENAI_WEB_SEARCH("OpenAI web search", "OpenAI web search로 후보를 발굴한 기사입니다.");

	private final String titleKo;
	private final String descriptionKo;

	NewsDiscoveryMethod(String titleKo, String descriptionKo) {
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
