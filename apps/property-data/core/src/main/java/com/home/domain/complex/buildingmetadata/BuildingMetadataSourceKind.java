package com.home.domain.complex.buildingmetadata;

public enum BuildingMetadataSourceKind {
	ODC_APT("ODC 단지", "공동주택 단지 식별과 동수·세대수·사용승인일 원천"),
	BLD_RECAP_TITLE("건축물 총괄표제부", "다동 단지 면적·비율 원천"),
	BLD_TITLE("건축물 표제부", "단일동 단지 면적·비율 원천");

	private final String titleKo;
	private final String descriptionKo;

	BuildingMetadataSourceKind(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public String titleKo() { return titleKo; }
	public String descriptionKo() { return descriptionKo; }
}
