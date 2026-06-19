package com.home.news;

import java.net.http.HttpClient;
import java.time.Clock;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.news.application.NewsMetadataClient;
import com.home.news.application.NewsSignalScorer;
import com.home.news.application.OneKeywordNewsCollectionService;
import com.home.news.infrastructure.external.naver.NaverNewsSearchClient;
import com.home.news.infrastructure.external.naver.NaverNewsSearchResponseParser;
import com.home.news.infrastructure.external.openai.OpenAiNewsSignalScorer;
import com.home.news.infrastructure.external.openai.NewsSignalStructuredOutputParser;
import com.home.news.infrastructure.persistence.JdbcNewsRepository;
import com.home.news.infrastructure.runner.RunOnceNewsApplicationRunner;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NewsRuntimeProperties.class, DataSourceProperties.class})
class NewsRuntimeConfiguration {

	@Bean
	Clock newsClock() {
		return Clock.systemDefaultZone();
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = "enabled", havingValue = "true")
	DataSource newsDataSource(DataSourceProperties properties) {
		return properties.initializeDataSourceBuilder().build();
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = "enabled", havingValue = "true")
	Flyway newsFlyway(DataSource dataSource) {
		return Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/news")
			.defaultSchema("news")
			.schemas("news")
			.table("flyway_schema_history")
			.cleanDisabled(true)
			.load();
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = "enabled", havingValue = "true")
	InitializingBean newsFlywayMigration(Flyway newsFlyway) {
		return newsFlyway::migrate;
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = "enabled", havingValue = "true")
	JdbcClient newsJdbcClient(DataSource dataSource) {
		return JdbcClient.create(dataSource);
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = "enabled", havingValue = "true")
	JdbcNewsRepository jdbcNewsRepository(JdbcClient jdbcClient) {
		return new JdbcNewsRepository(jdbcClient);
	}

	@Bean
	@ConditionalOnMissingBean
	HttpClient newsHttpClient() {
		return HttpClient.newHttpClient();
	}

	@Bean
	@ConditionalOnMissingBean
	NaverNewsSearchResponseParser naverNewsSearchResponseParser(ObjectMapper objectMapper) {
		return new NaverNewsSearchResponseParser(objectMapper);
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = "enabled", havingValue = "true")
	@ConditionalOnMissingBean(NewsMetadataClient.class)
	NewsMetadataClient naverNewsSearchClient(
		HttpClient httpClient,
		ObjectMapper objectMapper,
		NaverNewsSearchResponseParser parser,
		NewsRuntimeProperties properties
	) {
		return new NaverNewsSearchClient(httpClient, objectMapper, parser, properties);
	}

	@Bean
	@ConditionalOnMissingBean
	NewsSignalStructuredOutputParser newsSignalStructuredOutputParser(ObjectMapper objectMapper) {
		return new NewsSignalStructuredOutputParser(objectMapper);
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = "enabled", havingValue = "true")
	@ConditionalOnMissingBean(NewsSignalScorer.class)
	NewsSignalScorer openAiNewsSignalScorer(
		HttpClient httpClient,
		ObjectMapper objectMapper,
		NewsSignalStructuredOutputParser parser,
		NewsRuntimeProperties properties
	) {
		return new OpenAiNewsSignalScorer(httpClient, objectMapper, parser, properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = "enabled", havingValue = "true")
	OneKeywordNewsCollectionService oneKeywordNewsCollectionService(
		JdbcNewsRepository repository,
		NewsMetadataClient metadataClient,
		NewsSignalScorer signalScorer,
		NewsRuntimeProperties properties,
		Clock clock,
		ObjectMapper objectMapper
	) {
		return new OneKeywordNewsCollectionService(repository, metadataClient, signalScorer, properties, clock, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = {"enabled", "run-once.enabled"}, havingValue = "true")
	ApplicationRunner runOnceNewsApplicationRunner(
		OneKeywordNewsCollectionService service,
		NewsRuntimeProperties properties
	) {
		return new RunOnceNewsApplicationRunner(service, properties);
	}
}
