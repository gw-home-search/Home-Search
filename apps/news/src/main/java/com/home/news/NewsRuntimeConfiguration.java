package com.home.news;

import java.net.http.HttpClient;
import java.time.Clock;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.news.application.NewsMetadataClient;
import com.home.news.application.NewsSignalScorer;
import com.home.news.application.OneKeywordNewsCollectionService;
import com.home.news.application.HistoricalNewsResearchClient;
import com.home.news.application.HistoricalNewsResearchNoteGenerator;
import com.home.news.application.HistoricalNewsSeedImporter;
import com.home.news.infrastructure.external.naver.NaverNewsSearchClient;
import com.home.news.infrastructure.external.naver.NaverNewsSearchResponseParser;
import com.home.news.infrastructure.external.openai.HistoricalNewsResearchOutputParser;
import com.home.news.infrastructure.external.openai.OpenAiHistoricalNewsResearchClient;
import com.home.news.infrastructure.external.openai.OpenAiNewsSignalScorer;
import com.home.news.infrastructure.external.openai.NewsSignalStructuredOutputParser;
import com.home.news.infrastructure.external.openai.SpringAiHistoricalNewsPromptFactory;
import com.home.news.infrastructure.persistence.JdbcNewsRepository;
import com.home.news.infrastructure.runner.DailyNewsPipelineScheduler;
import com.home.news.infrastructure.runner.HistoricalNewsResearchSeedApplicationRunner;
import com.home.news.infrastructure.runner.RunOnceNewsApplicationRunner;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NewsRuntimeProperties.class, DataSourceProperties.class})
@EnableScheduling
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
	@ConditionalOnMissingBean
	HistoricalNewsResearchOutputParser historicalNewsResearchOutputParser(ObjectMapper objectMapper) {
		return new HistoricalNewsResearchOutputParser(objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean
	SpringAiHistoricalNewsPromptFactory springAiHistoricalNewsPromptFactory(ObjectMapper objectMapper) {
		return new SpringAiHistoricalNewsPromptFactory(objectMapper);
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
	@ConditionalOnMissingBean(HistoricalNewsResearchClient.class)
	HistoricalNewsResearchClient openAiHistoricalNewsResearchClient(
		HttpClient httpClient,
		ObjectMapper objectMapper,
		HistoricalNewsResearchOutputParser parser,
		SpringAiHistoricalNewsPromptFactory promptFactory,
		NewsRuntimeProperties properties
	) {
		return new OpenAiHistoricalNewsResearchClient(httpClient, objectMapper, parser, promptFactory, properties);
	}

	@Bean
	@ConditionalOnMissingBean
	HistoricalNewsResearchNoteGenerator historicalNewsResearchNoteGenerator(NewsRuntimeProperties properties, Clock clock) {
		return new HistoricalNewsResearchNoteGenerator(properties, clock);
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = "enabled", havingValue = "true")
	HistoricalNewsSeedImporter historicalNewsSeedImporter(
		JdbcNewsRepository repository,
		NewsRuntimeProperties properties,
		Clock clock,
		ObjectMapper objectMapper
	) {
		return new HistoricalNewsSeedImporter(repository, properties, clock, objectMapper);
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

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = {"enabled", "research-seed.enabled"}, havingValue = "true")
	ApplicationRunner historicalNewsResearchSeedApplicationRunner(
		HistoricalNewsResearchClient researchClient,
		HistoricalNewsResearchNoteGenerator noteGenerator,
		HistoricalNewsSeedImporter importer,
		NewsRuntimeProperties properties
	) {
		return new HistoricalNewsResearchSeedApplicationRunner(researchClient, noteGenerator, importer, properties);
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = {"enabled", "pipeline.daily.enabled"}, havingValue = "true")
	DailyNewsPipelineScheduler dailyNewsPipelineScheduler(
		OneKeywordNewsCollectionService service,
		NewsRuntimeProperties properties
	) {
		return new DailyNewsPipelineScheduler(service, properties);
	}
}
