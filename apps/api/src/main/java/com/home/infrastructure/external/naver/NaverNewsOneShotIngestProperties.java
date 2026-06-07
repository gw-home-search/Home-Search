package com.home.infrastructure.external.naver;

record NaverNewsOneShotIngestProperties(
	boolean enabled,
	String query,
	int display,
	int start,
	String sort,
	boolean preflightOnly
) {

	NaverNewsSearchRequest request() {
		return new NaverNewsSearchRequest(query, display, start, sort);
	}
}
