package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleObservationIngestService;
import com.home.application.news.NewsArticleObservationRepository;
import com.home.application.news.NewsArticleRelevanceGateService;
import com.home.application.news.NewsArticleRelevanceRepository;
import com.home.application.news.NewsSignalDatasetRepository;
import com.home.application.news.NewsSignalFeatureExtractionRepository;
import com.home.application.news.NewsSignalFeatureExtractionService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
			assertThat(context).hasSingleBean(NewsArticleRelevanceRepository.class);
			assertThat(context).hasSingleBean(NewsArticleRelevanceGateService.class);
			assertThat(context).hasSingleBean(NewsSignalFeatureExtractionRepository.class);
			assertThat(context).hasSingleBean(NewsSignalFeatureExtractionService.class);
			assertThat(context).hasSingleBean(NewsSignalDatasetRepository.class);
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
