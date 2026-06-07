package com.home.infrastructure.persistence.news;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleObservationIngestService;
import com.home.application.news.NewsArticleObservationRepository;
import com.home.application.news.NewsArticleRelevanceGateService;
import com.home.application.news.NewsArticleRelevancePolicy;
import com.home.application.news.NewsArticleRelevanceRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
	NewsRelevanceGateProperties newsRelevanceGateProperties(
		@Value("${home.news.relevance.enabled:false}") boolean enabled,
		@Value("${home.news.relevance.limit:100}") int limit
	) {
		return new NewsRelevanceGateProperties(enabled, limit);
	}

	@Bean
	@ConditionalOnProperty(name = "home.news.relevance.enabled", havingValue = "true")
	ApplicationRunner newsRelevanceGateApplicationRunner(
		NewsArticleRelevanceGateService service,
		NewsRelevanceGateProperties properties
	) {
		return new NewsRelevanceGateApplicationRunner(service, properties);
	}

	private static JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for news persistence");
		});
	}
}
