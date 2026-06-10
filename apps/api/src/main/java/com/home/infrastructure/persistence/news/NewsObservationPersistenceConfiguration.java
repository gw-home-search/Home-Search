package com.home.infrastructure.persistence.news;

import java.time.Clock;

import com.home.application.news.collection.NewsCollectionRepository;
import com.home.application.news.observation.NewsArticleObservationCleanupRepository;
import com.home.application.news.observation.NewsArticleObservationCleanupService;
import com.home.application.news.observation.NewsArticleObservationIngestService;
import com.home.application.news.observation.NewsArticleObservationRepository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class NewsObservationPersistenceConfiguration {

	@Bean
	@Lazy
	NewsArticleObservationRepository newsArticleObservationRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcNewsArticleObservationRepository(NewsPersistenceSupport.requiredJdbcClient(jdbcClientProvider));
	}

	@Bean
	@Lazy
	NewsCollectionRepository newsCollectionRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
		return new JdbcNewsCollectionRepository(NewsPersistenceSupport.requiredJdbcClient(jdbcClientProvider));
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
		return new JdbcNewsArticleObservationCleanupRepository(
			NewsPersistenceSupport.requiredJdbcClient(jdbcClientProvider)
		);
	}

	@Bean
	@Lazy
	NewsArticleObservationCleanupService newsArticleObservationCleanupService(
		NewsArticleObservationCleanupRepository repository
	) {
		return new NewsArticleObservationCleanupService(repository);
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
}
