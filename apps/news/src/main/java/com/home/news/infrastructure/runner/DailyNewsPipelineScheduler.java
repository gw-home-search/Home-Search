package com.home.news.infrastructure.runner;

import com.home.news.NewsRuntimeProperties;
import com.home.news.application.OneKeywordNewsCollectionService;
import org.springframework.scheduling.annotation.Scheduled;

public class DailyNewsPipelineScheduler {

	private final OneKeywordNewsCollectionService service;
	private final NewsRuntimeProperties properties;

	public DailyNewsPipelineScheduler(OneKeywordNewsCollectionService service, NewsRuntimeProperties properties) {
		this.service = service;
		this.properties = properties;
	}

	@Scheduled(cron = "${home.news.pipeline.daily.cron:0 0 4 * * *}", zone = "${home.news.pipeline.daily.zone:Asia/Seoul}")
	public void collectPilotQueries() {
		if (!properties.getPipeline().getDaily().isEnabled()) {
			return;
		}
		for (String query : properties.getPipeline().getDaily().getPilotQueries()) {
			if (query != null && !query.isBlank()) {
				service.collect(query);
			}
		}
	}
}
