package com.home.infrastructure.external.naver;

import java.time.OffsetDateTime;
import java.util.List;

record NaverNewsSearchPage(
	OffsetDateTime lastBuildDate,
	long total,
	int start,
	int display,
	List<NaverNewsSearchItem> items
) {

	NaverNewsSearchPage {
		items = List.copyOf(items == null ? List.of() : items);
	}
}
