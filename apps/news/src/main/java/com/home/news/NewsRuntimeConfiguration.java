package com.home.news;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.news.application.BigKindsCsvRegionMonthSignalGenerator;
import com.home.news.application.RegionAliasMatcher;
import com.home.news.application.RegionMonthSignalImporter;
import com.home.news.application.RegionMonthSignalJsonl;
import com.home.news.application.RegionMonthSignalObsidianExporter;
import com.home.news.application.RegionMonthSignalValidator;
import com.home.news.application.RegionMonthSignalWebWorklistGenerator;
import com.home.news.infrastructure.persistence.JdbcRegionMonthSignalRepository;
import com.home.news.infrastructure.runner.RegionMonthSignalApplicationRunner;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NewsRuntimeProperties.class, DataSourceProperties.class})
class NewsRuntimeConfiguration {

	@Bean
	@ConditionalOnExpression("'${home.news.enabled:false}' == 'true' && ('${home.news.region-month-signals.mode:}' == 'IMPORT_REGION_MONTH_SIGNALS' || '${home.news.region-month-signals.mode:}' == 'EXPORT_REGION_MONTH_SIGNALS')")
	DataSource newsDataSource(DataSourceProperties properties) {
		return properties.initializeDataSourceBuilder().build();
	}

	@Bean
	@ConditionalOnExpression("'${home.news.enabled:false}' == 'true' && ('${home.news.region-month-signals.mode:}' == 'IMPORT_REGION_MONTH_SIGNALS' || '${home.news.region-month-signals.mode:}' == 'EXPORT_REGION_MONTH_SIGNALS')")
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
	@ConditionalOnExpression("'${home.news.enabled:false}' == 'true' && ('${home.news.region-month-signals.mode:}' == 'IMPORT_REGION_MONTH_SIGNALS' || '${home.news.region-month-signals.mode:}' == 'EXPORT_REGION_MONTH_SIGNALS')")
	InitializingBean newsFlywayMigration(Flyway newsFlyway) {
		return newsFlyway::migrate;
	}

	@Bean
	@ConditionalOnExpression("'${home.news.enabled:false}' == 'true' && ('${home.news.region-month-signals.mode:}' == 'IMPORT_REGION_MONTH_SIGNALS' || '${home.news.region-month-signals.mode:}' == 'EXPORT_REGION_MONTH_SIGNALS')")
	JdbcClient newsJdbcClient(DataSource dataSource) {
		return JdbcClient.create(dataSource);
	}

	@Bean
	@ConditionalOnMissingBean
	RegionAliasMatcher regionAliasMatcher() {
		return new RegionAliasMatcher();
	}

	@Bean
	@ConditionalOnMissingBean
	RegionMonthSignalValidator regionMonthSignalValidator() {
		return new RegionMonthSignalValidator();
	}

	@Bean
	@ConditionalOnMissingBean
	RegionMonthSignalJsonl regionMonthSignalJsonl(ObjectMapper objectMapper, RegionMonthSignalValidator validator) {
		return new RegionMonthSignalJsonl(objectMapper, validator);
	}

	@Bean
	@ConditionalOnMissingBean
	BigKindsCsvRegionMonthSignalGenerator bigKindsCsvRegionMonthSignalGenerator(RegionAliasMatcher matcher) {
		return new BigKindsCsvRegionMonthSignalGenerator(matcher);
	}

	@Bean
	@ConditionalOnMissingBean
	RegionMonthSignalWebWorklistGenerator regionMonthSignalWebWorklistGenerator(ObjectMapper objectMapper) {
		return new RegionMonthSignalWebWorklistGenerator(objectMapper);
	}

	@Bean
	@ConditionalOnExpression("'${home.news.enabled:false}' == 'true' && ('${home.news.region-month-signals.mode:}' == 'IMPORT_REGION_MONTH_SIGNALS' || '${home.news.region-month-signals.mode:}' == 'EXPORT_REGION_MONTH_SIGNALS')")
	JdbcRegionMonthSignalRepository jdbcRegionMonthSignalRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		return new JdbcRegionMonthSignalRepository(jdbcClient, objectMapper);
	}

	@Bean
	@ConditionalOnExpression("'${home.news.enabled:false}' == 'true' && '${home.news.region-month-signals.mode:}' == 'IMPORT_REGION_MONTH_SIGNALS'")
	RegionMonthSignalImporter regionMonthSignalImporter(RegionMonthSignalJsonl jsonl, JdbcRegionMonthSignalRepository repository) {
		return new RegionMonthSignalImporter(jsonl, repository);
	}

	@Bean
	@ConditionalOnExpression("'${home.news.enabled:false}' == 'true' && '${home.news.region-month-signals.mode:}' == 'EXPORT_REGION_MONTH_SIGNALS'")
	RegionMonthSignalObsidianExporter regionMonthSignalObsidianExporter(JdbcRegionMonthSignalRepository repository) {
		return new RegionMonthSignalObsidianExporter(repository);
	}

	@Bean
	@ConditionalOnProperty(prefix = "home.news", name = {"enabled", "region-month-signals.enabled"}, havingValue = "true")
	ApplicationRunner regionMonthSignalApplicationRunner(
		BigKindsCsvRegionMonthSignalGenerator csvGenerator,
		RegionMonthSignalJsonl jsonl,
		RegionMonthSignalWebWorklistGenerator worklistGenerator,
		ObjectProvider<RegionMonthSignalImporter> importer,
		ObjectProvider<RegionMonthSignalObsidianExporter> exporter,
		NewsRuntimeProperties properties
	) {
		return new RegionMonthSignalApplicationRunner(csvGenerator, jsonl, worklistGenerator, importer, exporter, properties);
	}
}
