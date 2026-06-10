package com.home.infrastructure.persistence.news;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.export.NewsSignalObsidianExportRepository;
import com.home.application.news.export.NewsSignalObsidianExportService;
import com.home.application.news.export.NewsSignalObsidianExportWriter;
import com.home.application.news.export.NewsSignalObsidianMarkdownRenderer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class NewsObsidianExportPersistenceConfiguration {

	@Bean
	@Lazy
	NewsSignalObsidianExportRepository newsSignalObsidianExportRepository(
		ObjectProvider<JdbcClient> jdbcClientProvider,
		ObjectMapper objectMapper
	) {
		return new JdbcNewsSignalObsidianExportRepository(
			NewsPersistenceSupport.requiredJdbcClient(jdbcClientProvider),
			objectMapper
		);
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
			NewsPersistenceSupport.parseNullableDate(date),
			ZoneId.of(zone),
			maxRows
		);
	}

	@Bean
	@ConditionalOnStandaloneNewsStage(enabledProperty = "home.news.obsidian.export.enabled")
	ApplicationRunner newsSignalObsidianExportApplicationRunner(
		NewsSignalObsidianExportService service,
		NewsSignalObsidianExportProperties properties
	) {
		return new NewsSignalObsidianExportApplicationRunner(service, properties, Clock.systemUTC());
	}
}
