package com.home.infrastructure.persistence.news;

import java.time.Clock;
import java.time.OffsetDateTime;

import com.home.application.news.NewsArticleObservationCleanupResult;
import com.home.application.news.NewsArticleObservationCleanupService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class NewsArticleObservationCleanupApplicationRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(NewsArticleObservationCleanupApplicationRunner.class);

	private final NewsArticleObservationCleanupService service;
	private final NewsArticleObservationCleanupProperties properties;
	private final Clock clock;

	NewsArticleObservationCleanupApplicationRunner(
		NewsArticleObservationCleanupService service,
		NewsArticleObservationCleanupProperties properties,
		Clock clock
	) {
		this.service = service;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.enabled()) {
			return;
		}
		OffsetDateTime retentionCutoff = OffsetDateTime.now(clock).minus(properties.retentionWindow());
		NewsArticleObservationCleanupResult result = service.cleanup(retentionCutoff);
		log.info(
			"News article observation cleanup completed retentionCutoff={} purged={}",
			retentionCutoff,
			result.purged()
		);
	}

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.NEWS_OBSERVATION_CLEANUP;
	}
}
