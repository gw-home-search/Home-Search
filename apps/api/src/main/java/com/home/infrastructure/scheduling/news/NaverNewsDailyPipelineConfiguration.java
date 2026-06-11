package com.home.infrastructure.scheduling.news;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import com.home.application.news.collection.NewsCollectionRepository;
import com.home.application.news.export.NewsSignalObsidianExportService;
import com.home.application.news.relevance.NewsArticleRelevanceGateService;
import com.home.application.news.signal.NewsSignalFeatureExtractionService;
import com.home.infrastructure.external.naver.NaverNewsOneShotIngestRunner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "home.news.pipeline.daily.enabled", havingValue = "true")
public class NaverNewsDailyPipelineConfiguration {

	@Bean
	NaverNewsDailyPipelineProperties naverNewsDailyPipelineProperties(
		@Value("${home.news.pipeline.daily.enabled:false}") boolean enabled,
		@Value("${home.news.pipeline.daily.max-keywords:25}") int maxKeywords,
		@Value("${home.news.pipeline.daily.display:100}") int display,
		@Value("${home.news.pipeline.daily.sort:date}") String sort,
		@Value("${home.news.pipeline.daily.relevance-limit:${home.news.relevance.limit:100}}") int relevanceLimit,
		@Value("${home.news.pipeline.daily.feature-extraction-limit:${home.news.signal.extraction.limit:100}}")
		int featureExtractionLimit,
		@Value("${home.news.pipeline.daily.obsidian-output-root:${home.news.obsidian.export.output-root:}}")
		String obsidianOutputRoot,
		@Value("${home.news.pipeline.daily.obsidian-date:}") String obsidianDate,
		@Value("${home.news.pipeline.daily.zone:Asia/Seoul}") String zone,
		@Value("${home.news.pipeline.daily.obsidian-max-rows:${home.news.obsidian.export.max-rows:1000}}")
		int obsidianMaxRows
	) {
		return new NaverNewsDailyPipelineProperties(
			enabled,
			maxKeywords,
			display,
			sort,
			relevanceLimit,
			featureExtractionLimit,
			Path.of(obsidianOutputRoot),
			parseNullableDate(obsidianDate),
			ZoneId.of(zone),
			obsidianMaxRows
		);
	}

	@Bean
	NaverNewsDailyPipelineMessageFormatter naverNewsDailyPipelineMessageFormatter() {
		return new NaverNewsDailyPipelineMessageFormatter();
	}

	@Bean
	NaverNewsDailyPipelineNotifier naverNewsDailyPipelineNotifier(
		@Value("${home.news.pipeline.daily.hermes.enabled:false}") boolean enabled,
		@Value("${home.news.pipeline.daily.hermes.url:${HERMES_SLACK_URL:}}") String url,
		@Value("${home.news.pipeline.daily.hermes.auth-token:${HERMES_AUTH_TOKEN:}}") String authToken,
		@Value("${home.news.pipeline.daily.hermes.channel:${HERMES_SLACK_CHANNEL:}}") String channel,
		@Value("${home.news.pipeline.daily.hermes.connect-timeout-millis:3000}") int connectTimeoutMillis,
		@Value("${home.news.pipeline.daily.hermes.read-timeout-millis:3000}") int readTimeoutMillis
	) {
		if (!enabled || url == null || url.isBlank() || channel == null || channel.isBlank()) {
			return NaverNewsDailyPipelineNotifier.noop();
		}
		return new NaverNewsDailyPipelineHermesNotifier(
			url.trim(),
			authToken == null ? "" : authToken.trim(),
			channel.trim(),
			connectTimeoutMillis,
			readTimeoutMillis
		);
	}

	@Bean
	NaverNewsDailyPipelineRunner naverNewsDailyPipelineRunner(
		NewsCollectionRepository collectionRepository,
		NaverNewsOneShotIngestRunner ingestRunner,
		NewsArticleRelevanceGateService relevanceGateService,
		NewsSignalFeatureExtractionService featureExtractionService,
		NewsSignalObsidianExportService obsidianExportService,
		NaverNewsDailyPipelineNotifier notifier,
		NaverNewsDailyPipelineMessageFormatter formatter,
		NaverNewsDailyPipelineProperties properties
	) {
		return new NaverNewsDailyPipelineRunner(
			collectionRepository,
			ingestRunner,
			relevanceGateService,
			featureExtractionService,
			obsidianExportService,
			notifier,
			formatter,
			properties,
			Clock.system(properties.obsidianZoneId())
		);
	}

	@Bean
	NaverNewsDailyPipelineScheduler naverNewsDailyPipelineScheduler(NaverNewsDailyPipelineRunner runner) {
		return new NaverNewsDailyPipelineScheduler(runner);
	}

	private static LocalDate parseNullableDate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return LocalDate.parse(value);
	}
}
