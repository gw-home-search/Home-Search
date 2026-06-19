package com.home.news.infrastructure.persistence;

import java.util.Properties;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class JdbcNewsPostgresTestSupport {

	private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:16-3.4")
		.asCompatibleSubstituteFor("postgres");
	private static final String JDBC_OPTIONS = "-c lock_timeout=10000 -c statement_timeout=120000";

	protected DataSource dataSource;
	protected JdbcClient jdbcClient;

	protected static PostgreSQLContainer<?> newPostgisContainer() {
		return new PostgreSQLContainer<>(POSTGIS_IMAGE);
	}

	protected void initializeJdbc(PostgreSQLContainer<?> postgres) {
		dataSource = dataSource(postgres);
		jdbcClient = JdbcClient.create(dataSource);
	}

	protected Flyway newsFlyway() {
		return Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/news")
			.defaultSchema("news")
			.schemas("news")
			.table("flyway_schema_history")
			.cleanDisabled(false)
			.load();
	}

	protected long count(String qualifiedTableName) {
		return jdbcClient.sql("SELECT count(*) FROM " + qualifiedTableName)
			.query(Long.class)
			.single();
	}

	private DataSource dataSource(PostgreSQLContainer<?> postgres) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName(postgres.getDriverClassName());
		dataSource.setUrl(postgres.getJdbcUrl());
		dataSource.setUsername(postgres.getUsername());
		dataSource.setPassword(postgres.getPassword());
		dataSource.setConnectionProperties(connectionProperties());
		return dataSource;
	}

	private Properties connectionProperties() {
		Properties properties = new Properties();
		properties.setProperty("connectTimeout", "10");
		properties.setProperty("socketTimeout", "120");
		properties.setProperty("options", JDBC_OPTIONS);
		return properties;
	}
}
