package com.home.infrastructure.external.naver;

record NaverNewsSearchItem(
	String title,
	String originallink,
	String link,
	String description,
	String pubDate
) {

	String articleUrl() {
		if (originallink != null && !originallink.isBlank()) {
			return originallink;
		}
		return link;
	}
}
