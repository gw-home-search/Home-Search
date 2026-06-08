package com.home.infrastructure.persistence.news;

import com.home.application.news.signal.NewsSignalFeatureExtractionResult;
import com.home.application.news.signal.NewsSignalFeatureExtractionService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class NewsSignalFeatureExtractionApplicationRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(NewsSignalFeatureExtractionApplicationRunner.class);

	private final NewsSignalFeatureExtractionService service;
	private final NewsSignalFeatureExtractionProperties properties;

	NewsSignalFeatureExtractionApplicationRunner(
		NewsSignalFeatureExtractionService service,
		NewsSignalFeatureExtractionProperties properties
	) {
		this.service = service;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.enabled()) {
			return;
		}
		NewsSignalFeatureExtractionResult result = service.extractPending(properties.limit());
		log.info(
			"News signal feature extraction completed evaluated={} extracted={} statusUpdated={} duplicateFeatureSkipped={}",
			result.evaluated(),
			result.extracted(),
			result.statusUpdated(),
			result.duplicateFeatureSkipped()
		);
	}

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.NEWS_SIGNAL_FEATURE_EXTRACTION;
	}
}
