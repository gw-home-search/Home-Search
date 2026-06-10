package com.home.infrastructure.persistence.news;

import java.time.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.relevance.NewsArticleRelevanceGateService;
import com.home.application.news.relevance.NewsArticleRelevancePolicy;
import com.home.application.news.relevance.NewsArticleRelevanceRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class NewsRelevancePersistenceConfiguration {

	@Bean
	@Lazy
	NewsArticleRelevanceRepository newsArticleRelevanceRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectMapper objectMapper
	) {
		return new JdbcNewsRelevanceRepository(
			NewsPersistenceSupport.requiredJdbcClient(jdbcClientProvider),
			objectMapper
		);
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
	@ConditionalOnStandaloneNewsStage(enabledProperty = "home.news.relevance.enabled")
	ApplicationRunner newsRelevanceGateApplicationRunner(
		NewsArticleRelevanceGateService service,
		NewsRelevanceGateProperties properties
	) {
		return new NewsRelevanceGateApplicationRunner(service, properties);
	}
}
