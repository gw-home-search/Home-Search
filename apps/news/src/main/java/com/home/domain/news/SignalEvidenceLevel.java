package com.home.domain.news;

public enum SignalEvidenceLevel {

	title("제목", "기사 제목만 사용한 feature입니다."),
	snippet("스니펫", "공식 API snippet까지 사용한 feature입니다."),
	licensed_full_text("라이선스 전문", "라이선스가 허용한 full text 기반 feature입니다."),
	public_press_release("공공 보도자료", "공공 보도자료 기반 feature입니다.");

	private final String titleKo;
	private final String descriptionKo;

	SignalEvidenceLevel(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public boolean isSlice01Allowed() {
		return this == title || this == snippet;
	}

	public String titleKo() {
		return titleKo;
	}

	public String descriptionKo() {
		return descriptionKo;
	}
}
