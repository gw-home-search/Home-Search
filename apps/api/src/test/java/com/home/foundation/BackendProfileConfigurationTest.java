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
	@DisplayName("base profile keeps database auto-configuration and actuator exposure profile-scoped")
	void baseProfileDoesNotGloballyDisableDatabaseAutoConfiguration() throws IOException {
		Properties properties = load("application.yml");

		assertThat(properties.getProperty("spring.autoconfigure.exclude")).isNull();
		assertThat(properties.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration/api");
		assertThat(properties.getProperty("spring.profiles.default")).isEqualTo("local");
		assertThat(properties.getProperty("management.endpoints.web.exposure.include")).isNull();
		assertThat(properties.getProperty("management.prometheus.metrics.export.enabled")).isNull();
	}

	@Test
	@DisplayName("test profile is the only profile that disables database auto-configuration")
	void testProfileDisablesDatabaseAutoConfiguration() throws IOException {
		Properties properties = load("application-test.yml");

		assertThat(properties.getProperty("spring.autoconfigure.exclude"))
			.contains("DataSourceAutoConfiguration")
			.contains("FlywayAutoConfiguration");
		assertThat(properties.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
		assertThat(properties.getProperty("management.prometheus.metrics.export.enabled")).isEqualTo("true");
	}

	@Test
	@DisplayName("local profile wires PostgreSQL and V1 Flyway/local seed migrations through environment placeholders")
	void localProfileWiresPostgresAndFlywayMigrationLocation() throws IOException {
		Properties properties = load("application-local.yml");

		assertThat(properties.getProperty("spring.datasource.url")).isEqualTo("${DB_JDBC_URL}");
		assertThat(properties.getProperty("spring.datasource.username")).isEqualTo("${DB_USERNAME}");
		assertThat(properties.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD}");
		assertThat(properties.getProperty("spring.flyway.enabled")).isEqualTo("true");
		assertThat(properties.getProperty("spring.flyway.locations"))
			.isEqualTo("${SPRING_FLYWAY_LOCATIONS:classpath:db/migration/api,classpath:db/seed/local}");
		assertThat(properties.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
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
