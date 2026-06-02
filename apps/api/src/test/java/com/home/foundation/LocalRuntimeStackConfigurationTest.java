package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

class LocalRuntimeStackConfigurationTest {

	private static final Path LOCAL_PROMETHEUS = Path.of("..", "..", "infra", "prometheus.local.yml");

	private static final Path LOCAL_COMPOSE = Path.of("../../infra/docker-compose.local.yml");

	@Test
	@DisplayName("local compose stack은 secret 없이 PostGIS, API, Web, local seed를 연결한다")
	void localComposeStackWiresPostgisApiWebAndSeed() throws IOException {
		assertThat(LOCAL_COMPOSE).exists();

		String content = Files.readString(LOCAL_COMPOSE);

		assertThat(content).contains("postgis/postgis:16-3.4");
		assertThat(content).contains("DB_JDBC_URL: jdbc:postgresql://postgis:5432/${HOME_SEARCH_DB_NAME:-home_search}");
		assertThat(content).contains("COORDINATE_SOURCE_DB_JDBC_URL: ${COORDINATE_SOURCE_DB_JDBC_URL:-}");
		assertThat(content).contains("COORDINATE_SOURCE_DB_STATEMENT_TIMEOUT_MILLIS: ${COORDINATE_SOURCE_DB_STATEMENT_TIMEOUT_MILLIS:-3000}");
		assertThat(content).contains("COORDINATE_SOURCE_DB_READ_ONLY: ${COORDINATE_SOURCE_DB_READ_ONLY:-true}");
		assertThat(content).contains("SPRING_FLYWAY_LOCATIONS: classpath:db/migration/api,classpath:db/seed/local");
		assertThat(content).contains("SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS: ${SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS:-*:missing}");
		assertThat(content).contains("VITE_API_SERVER_IP: ${VITE_API_SERVER_IP:-http://localhost:8080}");
		assertThat(content).doesNotContain("APT_SERVICE_KEY");
		assertThat(content).doesNotContain(".env");
	}

	@Test
	@DisplayName("local compose stack은 Prometheus가 API actuator endpoint를 scrape하도록 연결한다")
	void localComposeStackWiresPrometheusScrapeConfig() throws IOException {
		assertThat(LOCAL_PROMETHEUS).exists();

		String compose = Files.readString(LOCAL_COMPOSE);
		assertThat(compose).contains("prom/prometheus");
		assertThat(compose).contains("./prometheus.local.yml:/etc/prometheus/prometheus.yml:ro");
		assertThat(compose).contains("${HOME_SEARCH_PROMETHEUS_PORT:-9090}:9090");

		Properties prometheus = loadYaml(LOCAL_PROMETHEUS);
		assertThat(prometheus.getProperty("scrape_configs[0].job_name")).isEqualTo("home-search-api");
		assertThat(prometheus.getProperty("scrape_configs[0].metrics_path")).isEqualTo("/actuator/prometheus");
		assertThat(prometheus.getProperty("scrape_configs[0].static_configs[0].targets[0]")).isEqualTo("api:8080");
	}

	private Properties loadYaml(Path path) {
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new FileSystemResource(path));
		return factory.getObject();
	}
}
