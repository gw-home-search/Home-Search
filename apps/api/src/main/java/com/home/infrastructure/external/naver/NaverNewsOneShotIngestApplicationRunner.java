package com.home.infrastructure.external.naver;

import com.home.application.news.NewsArticleObservationIngestResult;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class NaverNewsOneShotIngestApplicationRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(NaverNewsOneShotIngestApplicationRunner.class);

	private final NaverNewsOneShotIngestRunner runner;
	private final NaverNewsOneShotIngestProperties properties;
	private final NaverNewsSearchProperties searchProperties;

	NaverNewsOneShotIngestApplicationRunner(
		NaverNewsOneShotIngestRunner runner,
		NaverNewsOneShotIngestProperties properties,
		NaverNewsSearchProperties searchProperties
	) {
		this.runner = runner;
		this.properties = properties;
		this.searchProperties = searchProperties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.enabled()) {
			return;
		}
		NaverNewsSearchRequest request = properties.request();
		searchProperties.requiredClientId();
		searchProperties.requiredClientToken();
		if (properties.preflightOnly()) {
			log.info(
				"Naver News one-shot ingest preflight completed baseUrl={} path={} query={} display={} start={} sort={}",
				searchProperties.baseUrl(),
				searchProperties.path(),
				request.query(),
				request.display(),
				request.start(),
				request.sort()
			);
			return;
		}
		NewsArticleObservationIngestResult result = runner.ingest(request);
		log.info(
			"Naver News one-shot ingest completed query={} read={} observed={} duplicateSkipped={}",
			request.query(),
			result.read(),
			result.observed(),
			result.duplicateSkipped()
		);
	}

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.NEWS_ONE_SHOT_INGEST;
	}
}
