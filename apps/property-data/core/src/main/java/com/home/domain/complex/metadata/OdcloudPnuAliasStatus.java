package com.home.domain.complex.metadata;

/**
 * ODC 전용 PNU prefix 별칭의 운영 승인 상태다.
 */
public enum OdcloudPnuAliasStatus {
	PENDING("승인 대기", "자동 조회에 사용하지 않는 제안 상태"),
	APPROVED("승인", "ODC metadata 정확 조회에 사용할 수 있는 상태"),
	DISABLED("비활성", "더 이상 자동 조회에 사용하지 않는 상태");

	private final String titleKo;
	private final String descriptionKo;

	OdcloudPnuAliasStatus(String titleKo, String descriptionKo) {
		this.titleKo = titleKo;
		this.descriptionKo = descriptionKo;
	}

	public String titleKo() { return titleKo; }
	public String descriptionKo() { return descriptionKo; }
	public boolean isUsable() { return this == APPROVED; }
}
