package com.home.infrastructure.persistence.news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.signal.NewsSignalDatasetRepository;
import com.home.application.news.signal.NewsSignalFeatureExtractionPolicy;
import com.home.application.news.signal.NewsSignalFeatureExtractionRepository;
import com.home.application.news.signal.NewsSignalFeatureExtractionService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class NewsSignalPersistenceConfiguration {

	@Bean
	@Lazy
	NewsSignalFeatureExtractionRepository newsSignalFeatureExtractionRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectMapper objectMapper
	) {
		return new JdbcNewsSignalFeatureExtractionRepository(
			NewsPersistenceSupport.requiredJdbcClient(jdbcClientProvider),
			objectMapper
		);
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
		return new JdbcNewsSignalDatasetRepository(
			NewsPersistenceSupport.requiredJdbcClient(jdbcClientProvider),
			objectMapper
		);
	}

	@Bean
	NewsSignalFeatureExtractionProperties newsSignalFeatureExtractionProperties(
		@Value("${home.news.signal.extraction.enabled:false}") boolean enabled,
		@Value("${home.news.signal.extraction.limit:100}") int limit
	) {
		return new NewsSignalFeatureExtractionProperties(enabled, limit);
	}

	@Bean
	@ConditionalOnStandaloneNewsStage(enabledProperty = "home.news.signal.extraction.enabled")
	ApplicationRunner newsSignalFeatureExtractionApplicationRunner(
		NewsSignalFeatureExtractionService service,
		NewsSignalFeatureExtractionProperties properties
	) {
		return new NewsSignalFeatureExtractionApplicationRunner(service, properties);
	}
}
