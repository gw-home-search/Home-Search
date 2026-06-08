package com.home.infrastructure.persistence.news;

import java.time.Clock;
import java.time.LocalDate;

import com.home.application.news.export.NewsSignalObsidianExportCommand;
import com.home.application.news.export.NewsSignalObsidianExportResult;
import com.home.application.news.export.NewsSignalObsidianExportService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class NewsSignalObsidianExportApplicationRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(NewsSignalObsidianExportApplicationRunner.class);

	private final NewsSignalObsidianExportService service;
	private final NewsSignalObsidianExportProperties properties;
	private final Clock clock;

	NewsSignalObsidianExportApplicationRunner(
		NewsSignalObsidianExportService service,
		NewsSignalObsidianExportProperties properties,
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
		LocalDate exportDate = properties.date() == null
			? LocalDate.now(clock.withZone(properties.zoneId()))
			: properties.date();
		NewsSignalObsidianExportResult result = service.exportDaily(new NewsSignalObsidianExportCommand(
			properties.outputRoot(),
			exportDate,
			properties.zoneId(),
			properties.maxRows()
		));
		log.info(
			"News signal Obsidian export completed date={} path={} features={} articles={} truncated={}",
			result.date(),
			result.path(),
			result.featureCount(),
			result.articleCount(),
			result.truncated()
		);
	}

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.NEWS_OBSIDIAN_EXPORT;
	}
}
