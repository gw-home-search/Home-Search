package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.observation.NewsArticleObservationCleanupRepository;
import com.home.application.news.observation.NewsArticleObservationCleanupService;
import com.home.application.news.observation.NewsArticleObservationIngestService;
import com.home.application.news.observation.NewsArticleObservationRepository;
import com.home.application.news.relevance.NewsArticleRelevanceGateService;
import com.home.application.news.relevance.NewsArticleRelevanceRepository;
import com.home.application.news.signal.NewsSignalDatasetRepository;
import com.home.application.news.signal.NewsSignalFeatureExtractionRepository;
import com.home.application.news.signal.NewsSignalFeatureExtractionService;
import com.home.application.news.export.NewsSignalObsidianExportRepository;
import com.home.application.news.export.NewsSignalObsidianExportService;
import com.home.application.news.export.NewsSignalObsidianExportWriter;
import com.home.application.news.export.NewsSignalObsidianMarkdownRenderer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

class NewsPersistenceConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			NewsPersistenceConfiguration.class,
			LateJdbcClientConfiguration.class
		);

	@Test
	@DisplayName("News persistence bean은 JdbcClient bean 선언 순서와 무관하게 등록된다")
	void newsPersistenceBeansAreRegisteredWhenJdbcClientBeanDefinitionComesLater() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(NewsArticleObservationRepository.class);
			assertThat(context).hasSingleBean(NewsArticleObservationIngestService.class);
			assertThat(context).hasSingleBean(NewsArticleObservationCleanupRepository.class);
			assertThat(context).hasSingleBean(NewsArticleObservationCleanupService.class);
			assertThat(context).hasSingleBean(NewsArticleRelevanceRepository.class);
			assertThat(context).hasSingleBean(NewsArticleRelevanceGateService.class);
			assertThat(context).hasSingleBean(NewsSignalFeatureExtractionRepository.class);
			assertThat(context).hasSingleBean(NewsSignalFeatureExtractionService.class);
			assertThat(context).hasSingleBean(NewsSignalDatasetRepository.class);
			assertThat(context).hasSingleBean(NewsSignalObsidianExportRepository.class);
			assertThat(context).hasSingleBean(NewsSignalObsidianMarkdownRenderer.class);
			assertThat(context).hasSingleBean(NewsSignalObsidianExportWriter.class);
			assertThat(context).hasSingleBean(NewsSignalObsidianExportService.class);
		});
	}

	@Test
	@DisplayName("News persistence configuration은 obsidian export가 enabled일 때만 runner를 등록한다")
	void obsidianExportRunnerIsRegisteredOnlyWhenEnabled() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(NewsSignalObsidianExportApplicationRunner.class);
		});

		contextRunner
			.withPropertyValues(
				"home.news.obsidian.export.enabled=true",
				"home.news.obsidian.export.output-root=/tmp/home-search-obsidian-test"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(NewsSignalObsidianExportApplicationRunner.class);
				assertThat(context).hasSingleBean(ApplicationRunner.class);
			});
	}

	@Test
	@DisplayName("News persistence configuration은 pipeline 설정이면 개별 signal stage runner를 등록하지 않는다")
	void pipelineSuppressesIndividualSignalStageRunners() {
		contextRunner
			.withPropertyValues(
				"home.news.pipeline.enabled=true",
				"home.news.relevance.enabled=true",
				"home.news.signal.extraction.enabled=true",
				"home.news.obsidian.export.enabled=true",
				"home.news.obsidian.export.output-root=/tmp/home-search-obsidian-test"
			)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(NewsRelevanceGateApplicationRunner.class);
				assertThat(context).doesNotHaveBean(NewsSignalFeatureExtractionApplicationRunner.class);
				assertThat(context).doesNotHaveBean(NewsSignalObsidianExportApplicationRunner.class);
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class LateJdbcClientConfiguration {

		@Bean
		JdbcClient jdbcClient() {
			return mock(JdbcClient.class);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}
}
