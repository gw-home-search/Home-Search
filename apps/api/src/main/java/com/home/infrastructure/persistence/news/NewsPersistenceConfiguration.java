package com.home.infrastructure.persistence.news;

import com.home.application.news.NewsArticleObservationIngestService;
import com.home.application.news.NewsArticleObservationRepository;

import org.springframework.beans.factory.ObjectProvider;
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

	private static JdbcClient requiredJdbcClient(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return jdbcClientProvider.getIfAvailable(() -> {
			throw new IllegalStateException("JdbcClient is required for news persistence");
		});
	}
}
