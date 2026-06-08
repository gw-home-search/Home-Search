package com.home.infrastructure.external.naver;

import com.home.application.news.observation.NewsArticleObservationIngestResult;
import com.home.application.news.observation.NewsArticleObservationIngestService;

class NaverNewsOneShotIngestRunner {

	private final NaverNewsSearchClient client;
	private final NaverNewsObservationMapper mapper;
	private final NewsArticleObservationIngestService ingestService;

	NaverNewsOneShotIngestRunner(
		NaverNewsSearchClient client,
		NaverNewsObservationMapper mapper,
		NewsArticleObservationIngestService ingestService
	) {
		this.client = client;
		this.mapper = mapper;
		this.ingestService = ingestService;
	}

	NewsArticleObservationIngestResult ingest(NaverNewsSearchRequest request) {
		return ingestService.ingest(mapper.toObservationCommands(client.search(request)));
	}
}
