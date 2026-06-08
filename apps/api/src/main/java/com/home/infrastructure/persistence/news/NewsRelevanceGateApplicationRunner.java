package com.home.infrastructure.persistence.news;

import com.home.application.news.relevance.NewsArticleRelevanceGateResult;
import com.home.application.news.relevance.NewsArticleRelevanceGateService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class NewsRelevanceGateApplicationRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(NewsRelevanceGateApplicationRunner.class);

	private final NewsArticleRelevanceGateService service;
	private final NewsRelevanceGateProperties properties;

	NewsRelevanceGateApplicationRunner(
		NewsArticleRelevanceGateService service,
		NewsRelevanceGateProperties properties
	) {
		this.service = service;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.enabled()) {
			return;
		}
		NewsArticleRelevanceGateResult result = service.evaluateObserved(properties.limit());
		log.info(
			"News relevance gate completed evaluated={} kept={} reviewed={} skippedIrrelevant={} statusUpdated={} duplicateDecisionSkipped={}",
			result.evaluated(),
			result.kept(),
			result.reviewed(),
			result.skippedIrrelevant(),
			result.statusUpdated(),
			result.decisionDuplicateSkipped()
		);
	}

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.NEWS_RELEVANCE_GATE;
	}
}
