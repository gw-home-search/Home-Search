package com.home.infrastructure.persistence.ingest;

import java.util.Properties;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class JdbcPostgresContainerSupport {

	private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:16-3.4")
		.asCompatibleSubstituteFor("postgres");
	private static final String JDBC_OPTIONS = "-c lock_timeout=10000 -c statement_timeout=120000";

	protected DataSource dataSource;
	protected JdbcClient jdbcClient;
	protected TransactionTemplate transactionTemplate;

	protected static PostgreSQLContainer<?> newPostgisContainer() {
		return new PostgreSQLContainer<>(POSTGIS_IMAGE);
	}

	protected void initializeJdbc(PostgreSQLContainer<?> postgres) {
		dataSource = dataSource(postgres);
		jdbcClient = JdbcClient.create(dataSource);
		transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	protected Flyway flyway(MigrationVersion target) {
		FluentConfiguration configuration = Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/api")
			.schemas("public", "reference")
			.defaultSchema("public")
			.cleanDisabled(false);
		if (target != null) {
			configuration.target(target);
		}
		return configuration.load();
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
