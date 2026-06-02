package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

class BackendProfileConfigurationTest {

	private static final Path RESOURCES = Path.of("src/main/resources");

	@Test
	@DisplayName("base profile은 database auto-configuration과 actuator exposure를 profile scope로 유지한다")
	void baseProfileDoesNotGloballyDisableDatabaseAutoConfiguration() throws IOException {
		Properties properties = load("application.yml");

		assertThat(properties.getProperty("spring.autoconfigure.exclude")).isNull();
		assertThat(properties.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration/api");
		assertThat(properties.getProperty("spring.profiles.default")).isEqualTo("local");
		assertThat(properties.getProperty("management.endpoints.web.exposure.include")).isNull();
		assertThat(properties.getProperty("management.prometheus.metrics.export.enabled")).isNull();
	}

	@Test
	@DisplayName("test profile만 database auto-configuration을 비활성화한다")
	void testProfileDisablesDatabaseAutoConfiguration() throws IOException {
		Properties properties = load("application-test.yml");

		assertThat(properties.getProperty("spring.autoconfigure.exclude"))
			.contains("DataSourceAutoConfiguration")
			.contains("FlywayAutoConfiguration");
		assertThat(properties.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
		assertThat(properties.getProperty("management.prometheus.metrics.export.enabled")).isEqualTo("true");
	}

	@Test
	@DisplayName("local profile은 PostgreSQL과 Flyway/local seed migration을 environment placeholder로 연결한다")
	void localProfileWiresPostgresAndFlywayMigrationLocation() throws IOException {
		Properties properties = load("application-local.yml");

		assertThat(properties.getProperty("spring.datasource.url")).isEqualTo("${DB_JDBC_URL}");
		assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
		assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
		assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("true");
		assertThat(properties.getProperty("spring.flyway.locations"))
			.isEqualTo("${SPRING_FLYWAY_LOCATIONS:classpath:db/migration/api,classpath:db/seed/local}");
		assertThat(properties.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
		assertThat(properties.getProperty("spring.flyway.ignore-migration-patterns"))
			.isEqualTo("${SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS:*:missing}");
		assertThat(properties.getProperty("home.coordinate-source.db.jdbc-url"))
			.isEqualTo("${COORDINATE_SOURCE_DB_JDBC_URL:}");
		assertThat(properties.getProperty("home.coordinate-source.db.username"))
			.isEqualTo("${COORDINATE_SOURCE_DB_USERNAME:${DB_USERNAME}}");
		assertThat(properties.getProperty("home.coordinate-source.db.password"))
			.isEqualTo("${COORDINATE_SOURCE_DB_PASSWORD:${DB_PASSWORD}}");
		assertThat(properties.getProperty("home.coordinate-source.db.connect-timeout-seconds"))
			.isEqualTo("${COORDINATE_SOURCE_DB_CONNECT_TIMEOUT_SECONDS:5}");
		assertThat(properties.getProperty("home.coordinate-source.db.socket-timeout-seconds"))
			.isEqualTo("${COORDINATE_SOURCE_DB_SOCKET_TIMEOUT_SECONDS:10}");
		assertThat(properties.getProperty("home.coordinate-source.db.lock-timeout-millis"))
			.isEqualTo("${COORDINATE_SOURCE_DB_LOCK_TIMEOUT_MILLIS:1000}");
		assertThat(properties.getProperty("home.coordinate-source.db.statement-timeout-millis"))
			.isEqualTo("${COORDINATE_SOURCE_DB_STATEMENT_TIMEOUT_MILLIS:3000}");
		assertThat(properties.getProperty("home.coordinate-source.db.read-only"))
			.isEqualTo("${COORDINATE_SOURCE_DB_READ_ONLY:true}");
		assertThat(properties.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
		assertThat(properties.getProperty("management.prometheus.metrics.export.enabled")).isEqualTo("true");
	}

	private Properties load(String fileName) throws IOException {
		Path path = RESOURCES.resolve(fileName);
		assertThat(path).exists();
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new FileSystemResource(path));
		return factory.getObject();
	}
}
