package com.home.infrastructure.persistence.news;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleObservationCleanupRepository;
import com.home.application.news.NewsArticleObservationCleanupService;
import com.home.application.news.NewsArticleObservationIngestService;
import com.home.application.news.NewsArticleObservationRepository;
import com.home.application.news.NewsArticleRelevanceGateService;
import com.home.application.news.NewsArticleRelevancePolicy;
import com.home.application.news.NewsArticleRelevanceRepository;
import com.home.application.news.NewsSignalDatasetRepository;
import com.home.application.news.NewsSignalFeatureExtractionPolicy;
import com.home.application.news.NewsSignalFeatureExtractionRepository;
import com.home.application.news.NewsSignalFeatureExtractionService;
import com.home.application.news.NewsSignalObsidianExportRepository;
import com.home.application.news.NewsSignalObsidianExportService;
import com.home.application.news.NewsSignalObsidianExportWriter;
import com.home.application.news.NewsSignalObsidianMarkdownRenderer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class NewsPersistenceConfiguration {

	@Bean
	@Lazy
	NewsArticleObservationRepository newsArticleObservationRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcNewsArticleObservationRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	NewsArticleObservationIngestService newsArticleObservationIngestService(
		NewsArticleObservationRepository repository
	) {
		return new NewsArticleObservationIngestService(repository);
	}

	@Bean
	@Lazy
	NewsArticleObservationCleanupRepository newsArticleObservationCleanupRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider
	) {
		return new JdbcNewsArticleObservationCleanupRepository(requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	NewsArticleObservationCleanupService newsArticleObservationCleanupService(
		NewsArticleObservationCleanupRepository repository
	) {
		return new NewsArticleObservationCleanupService(repository);
	}

	@Bean
	@Lazy
	NewsArticleRelevanceRepository newsArticleRelevanceRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectMapper objectMapper
	) {
		return new JdbcNewsRelevanceRepository(requiredJdbcClient(jdbcClientProvider), objectMapper);
	}

	@Bean
	NewsArticleRelevancePolicy newsArticleRelevancePolicy() {
		return NewsArticleRelevancePolicy.defaultPolicy();
	}

	@Bean
	@Lazy
	NewsArticleRelevanceGateService newsArticleRelevanceGateService(
		NewsArticleRelevanceRepository repository,
		NewsArticleRelevancePolicy policy
	) {
		return new NewsArticleRelevanceGateService(repository, policy, Clock.systemUTC());
	}

	@Bean
	@Lazy
	NewsSignalFeatureExtractionRepository newsSignalFeatureExtractionRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectMapper objectMapper
	) {
		return new JdbcNewsSignalFeatureExtractionRepository(requiredJdbcClient(jdbcClientProvider), objectMapper);
	}

	@Bean
	NewsSignalFeatureExtractionPolicy newsSignalFeatureExtractionPolicy() {
		return NewsSignalFeatureExtractionPolicy.defaultPolicy();
	}

	@Bean
	@Lazy
	NewsSignalFeatureExtractionService newsSignalFeatureExtractionService(
		NewsSignalFeatureExtractionRepository repository,
		NewsSignalFeatureExtractionPolicy policy
	) {
		return new NewsSignalFeatureExtractionService(repository, policy);
	}

	@Bean
	@Lazy
	NewsSignalDatasetRepository newsSignalDatasetRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectMapper objectMapper
	) {
		return new JdbcNewsSignalDatasetRepository(requiredJdbcClient(jdbcClientProvider), objectMapper);
	}

	@Bean
	@Lazy
	NewsSignalObsidianExportRepository newsSignalObsidianExportRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectMapper objectMapper
	) {
		return new JdbcNewsSignalObsidianExportRepository(requiredJdbcClient(jdbcClientProvider), objectMapper);
	}

	@Bean
	NewsSignalObsidianMarkdownRenderer newsSignalObsidianMarkdownRenderer() {
		return new NewsSignalObsidianMarkdownRenderer();
	}

	@Bean
	NewsSignalObsidianExportWriter newsSignalObsidianExportWriter() {
		return new FileSystemNewsSignalObsidianExportWriter();
	}

	@Bean
	@Lazy
	NewsSignalObsidianExportService newsSignalObsidianExportService(
		NewsSignalObsidianExportRepository repository,
		NewsSignalObsidianMarkdownRenderer renderer,
		NewsSignalObsidianExportWriter writer
	) {
		return new NewsSignalObsidianExportService(repository, renderer, writer);
	}

	@Bean
	NewsRelevanceGateProperties newsRelevanceGateProperties(
		@Value("${home.news.relevance.enabled:false}") boolean enabled,
		@Value("${home.news.relevance.limit:100}") int limit
	) {
		return new NewsRelevanceGateProperties(enabled, limit);
	}

	@Bean
	@ConditionalOnExpression("${home.news.relevance.enabled:false} && !${home.news.pipeline.enabled:false}")
	ApplicationRunner newsRelevanceGateApplicationRunner(
		NewsArticleRelevanceGateService service,
		NewsRelevanceGateProperties properties
	) {
		return new NewsRelevanceGateApplicationRunner(service, properties);
	}

	@Bean
	NewsSignalFeatureExtractionProperties newsSignalFeatureExtractionProperties(
		@Value("${home.news.signal.extraction.enabled:false}") boolean enabled,
		@Value("${home.news.signal.extraction.limit:100}") int limit
	) {
		return new NewsSignalFeatureExtractionProperties(enabled, limit);
	}

	@Bean
	@ConditionalOnExpression("${home.news.signal.extraction.enabled:false} && !${home.news.pipeline.enabled:false}")
	ApplicationRunner newsSignalFeatureExtractionApplicationRunner(
		NewsSignalFeatureExtractionService service,
		NewsSignalFeatureExtractionProperties properties
	) {
		return new NewsSignalFeatureExtractionApplicationRunner(service, properties);
	}

	@Bean
	NewsArticleObservationCleanupProperties newsArticleObservationCleanupProperties(
		@Value("${home.news.observation.cleanup.enabled:false}") boolean enabled,
		@Value("${home.news.observation.cleanup.retention-window:7d}") String retentionWindow
	) {
		return new NewsArticleObservationCleanupProperties(enabled, DurationStyle.detectAndParse(retentionWindow));
	}

	@Bean
	@ConditionalOnProperty(name = "home.news.observation.cleanup.enabled", havingValue = "true")
	ApplicationRunner newsArticleObservationCleanupApplicationRunner(
		NewsArticleObservationCleanupService service,
		NewsArticleObservationCleanupProperties properties
	) {
		return new NewsArticleObservationCleanupApplicationRunner(service, properties, Clock.systemUTC());
	}

	@Bean
	NewsSignalObsidianExportProperties newsSignalObsidianExportProperties(
		@Value("${home.news.obsidian.export.enabled:false}") boolean enabled,
		@Value("${home.news.obsidian.export.output-root:}") String outputRoot,
		@Value("${home.news.obsidian.export.date:}") String date,
		@Value("${home.news.obsidian.export.zone:Asia/Seoul}") String zone,
		@Value("${home.news.obsidian.export.max-rows:1000}") int maxRows
	) {
		return new NewsSignalObsidianExportProperties(
			enabled,
			Path.of(outputRoot),
			parseNullableDate(date),
			ZoneId.of(zone),
			maxRows
		);
	}

	@Bean
	@ConditionalOnExpression("${home.news.obsidian.export.enabled:false} && !${home.news.pipeline.enabled:false}")
	ApplicationRunner newsSignalObsidianExportApplicationRunner(
		NewsSignalObsidianExportService service,
		NewsSignalObsidianExportProperties properties
	) {
		return new NewsSignalObsidianExportApplicationRunner(service, properties, Clock.systemUTC());
	}

	private static JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for news persistence");
		});
	}

	private static LocalDate parseNullableDate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return LocalDate.parse(value);
	}
}
