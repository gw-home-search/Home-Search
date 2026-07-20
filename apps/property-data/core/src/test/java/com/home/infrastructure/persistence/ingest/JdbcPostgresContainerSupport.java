package com.home.infrastructure.persistence.ingest;

import java.time.Duration;
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

    protected static final String AI_READER_ROLE = "home_search_ai_reader";
    protected static final String AI_READER_PASSWORD = "ai-reader-test-only";
    protected static final String PROPERTY_RUNTIME_ROLE = "home_search_property_runtime";
    protected static final String PROPERTY_RUNTIME_PASSWORD = "property-runtime-test-only";

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");
    private static final String JDBC_OPTIONS = "-c lock_timeout=10000 -c statement_timeout=120000";

    protected DataSource dataSource;
    protected JdbcClient jdbcClient;
    protected TransactionTemplate transactionTemplate;

    protected static PostgreSQLContainer<?> newPostgisContainer() {
        return new PostgreSQLContainer<>(POSTGIS_IMAGE).withStartupTimeout(Duration.ofMinutes(3));
    }

    protected void initializeJdbc(PostgreSQLContainer<?> postgres) {
        dataSource = dataSource(postgres);
        jdbcClient = JdbcClient.create(dataSource);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    protected Flyway flyway(MigrationVersion target) {
        return flyway(target, System.getProperty("propertyDataMigrationLocation"));
    }

    protected Flyway flyway(MigrationVersion target, String location) {
        return flyway(target, location, "public", "reference", "batch", "ai_read");
    }

    protected Flyway flyway(MigrationVersion target, String location, String... schemas) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                .schemas(schemas)
                .defaultSchema("public")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    protected void ensureAiReaderRole() {
        Boolean exists = jdbcClient
                .sql("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :role)")
                .param("role", AI_READER_ROLE)
                .query(Boolean.class)
                .single();
        if (!exists) {
            jdbcClient
                    .sql("CREATE ROLE " + AI_READER_ROLE + " LOGIN PASSWORD '" + AI_READER_PASSWORD + "'")
                    .update();
        }
        jdbcClient
                .sql("ALTER ROLE " + AI_READER_ROLE
                        + " NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
                        + AI_READER_PASSWORD + "'")
                .update();
    }

    protected void ensurePropertyRuntimeRole() {
        Boolean exists = jdbcClient
                .sql("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :role)")
                .param("role", PROPERTY_RUNTIME_ROLE)
                .query(Boolean.class)
                .single();
        if (!exists) {
            jdbcClient
                    .sql("CREATE ROLE " + PROPERTY_RUNTIME_ROLE + " LOGIN PASSWORD '" + PROPERTY_RUNTIME_PASSWORD + "'")
                    .update();
        }
        jdbcClient
                .sql("ALTER ROLE " + PROPERTY_RUNTIME_ROLE
                        + " INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"
                        + PROPERTY_RUNTIME_PASSWORD + "'")
                .update();
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
