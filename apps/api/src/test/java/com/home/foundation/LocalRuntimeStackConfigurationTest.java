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
	private static final Path LOCAL_LOKI = Path.of("..", "..", "infra", "loki.local.yml");
	private static final Path LOCAL_ALLOY = Path.of("..", "..", "infra", "alloy.local.alloy");
	private static final Path GRAFANA_DATASOURCES = Path.of(
		"..", "..", "infra", "grafana", "provisioning", "datasources", "datasources.yml"
	);
	private static final Path GRAFANA_DASHBOARDS = Path.of(
		"..", "..", "infra", "grafana", "provisioning", "dashboards", "dashboards.yml"
	);
	private static final Path GRAFANA_HOME_SEARCH_DASHBOARD = Path.of(
		"..", "..", "infra", "grafana", "dashboards", "home-search-overview.json"
	);

	@Test
	@DisplayName("local compose stack은 secret 없이 PostGIS, API, Web, project migration을 연결한다")
	void localComposeStackWiresPostgisApiWebAndSeed() throws IOException {
		assertThat(LOCAL_COMPOSE).exists();

		String content = Files.readString(LOCAL_COMPOSE);

		assertThat(content).contains("postgis/postgis:16-3.4");
		assertThat(content).contains("DB_JDBC_URL: jdbc:postgresql://postgis:5432/${HOME_SEARCH_DB_NAME:-home_search}");
		assertThat(content).contains("env_file:");
		assertThat(content).contains("- ../apps/api/ops/local-runtime.env.example");
		assertThat(content).contains("- ${HOME_SEARCH_API_ENV_FILE:-../apps/api/ops/local-runtime.override.env.example}");
		assertThat(content).contains("COORDINATE_SOURCE_DB_JDBC_URL: jdbc:postgresql://postgis:5432/${COORDINATE_SOURCE_DB_NAME:-home_search_coordinate_full_durable_20260527182147}");
		assertThat(content).contains("COORDINATE_SOURCE_DB_STATEMENT_TIMEOUT_MILLIS: ${COORDINATE_SOURCE_DB_STATEMENT_TIMEOUT_MILLIS:-3000}");
		assertThat(content).contains("COORDINATE_SOURCE_DB_READ_ONLY: ${COORDINATE_SOURCE_DB_READ_ONLY:-true}");
		assertThat(content).contains("HOME_INGEST_RTMS_ENABLED: ${HOME_INGEST_RTMS_ENABLED:-false}");
		assertThat(content).contains("HOME_INGEST_RTMS_MODE: ${HOME_INGEST_RTMS_MODE:-one-shot}");
		assertThat(content).contains("HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY: ${HOME_INGEST_RTMS_ALLOW_COORDINATE_PENDING_ONLY:-false}");
		assertThat(content).contains("SPRING_FLYWAY_LOCATIONS: classpath:db/migration/api");
		assertThat(content).contains("SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS: ${SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS:-*:missing}");
		assertThat(content).contains("VITE_API_SERVER_IP: ${VITE_API_SERVER_IP:-http://localhost:8080}");
		assertThat(content).doesNotContain("APT_SERVICE_KEY:");
		assertThat(content).doesNotContain("VW_SERVICE_KEY:");
	}

	@Test
	@DisplayName("local compose stack은 Prometheus가 API actuator endpoint를 scrape하도록 연결한다")
	void localComposeStackWiresPrometheusScrapeConfig() throws IOException {
		assertThat(LOCAL_PROMETHEUS).exists();

		String compose = Files.readString(LOCAL_COMPOSE);
		assertThat(compose).contains("prom/prometheus");
		assertThat(compose).contains("./prometheus.local.yml:/etc/prometheus/prometheus.yml:ro");
		assertThat(compose).contains("127.0.0.1:${HOME_SEARCH_PROMETHEUS_PORT:-9090}:9090");

		Properties prometheus = loadYaml(LOCAL_PROMETHEUS);
		assertThat(prometheus.getProperty("scrape_configs[0].job_name")).isEqualTo("home-search-api");
		assertThat(prometheus.getProperty("scrape_configs[0].metrics_path")).isEqualTo("/actuator/prometheus");
		assertThat(prometheus.getProperty("scrape_configs[0].static_configs[0].targets[0]")).isEqualTo("api:8080");
	}

	@Test
	@DisplayName("local monitoring stack은 Grafana, Loki, Alloy와 provisioning을 연결한다")
	void localComposeStackWiresGrafanaLokiAndAlloy() throws IOException {
		assertThat(LOCAL_LOKI).exists();
		assertThat(LOCAL_ALLOY).exists();
		assertThat(GRAFANA_DATASOURCES).exists();
		assertThat(GRAFANA_DASHBOARDS).exists();
		assertThat(GRAFANA_HOME_SEARCH_DASHBOARD).exists();

		String compose = Files.readString(LOCAL_COMPOSE);
		assertThat(compose).contains("grafana/grafana:13.0.2");
		assertThat(compose).contains("grafana/loki:3.7.0");
		assertThat(compose).contains("grafana/alloy:${HOME_SEARCH_ALLOY_IMAGE_TAG:-v${HOME_SEARCH_ALLOY_VERSION:-1.16.3}}");
		assertThat(compose).contains("./loki.local.yml:/etc/loki/local-config.yml:ro");
		assertThat(compose).contains("./alloy.local.alloy:/etc/alloy/config.alloy:ro");
		assertThat(compose).contains("./grafana/provisioning:/etc/grafana/provisioning:ro");
		assertThat(compose).contains("./grafana/dashboards:/var/lib/grafana/dashboards:ro");
		assertThat(compose).contains("127.0.0.1:${HOME_SEARCH_GRAFANA_PORT:-3000}:3000");
		assertThat(compose).contains("127.0.0.1:${HOME_SEARCH_LOKI_PORT:-3100}:3100");
		assertThat(compose).contains("127.0.0.1:${HOME_SEARCH_ALLOY_PORT:-12345}:12345");
		assertThat(compose).contains("/var/run/docker.sock:/var/run/docker.sock:ro");
		assertThat(compose).contains("home-search-grafana-data:");
		assertThat(compose).contains("home-search-loki-data:");
		assertThat(compose).doesNotContain("promtail");

		String alloy = Files.readString(LOCAL_ALLOY);
		assertThat(alloy).contains("discovery.docker");
		assertThat(alloy).contains("loki.source.docker");
		assertThat(alloy).contains("loki.write");
		assertThat(alloy).contains("http://loki:3100/loki/api/v1/push");
		assertThat(alloy).contains("regex = \"/home-search-.*\"");
		assertThat(alloy).contains("target_label = \"container\"");
		assertThat(alloy).contains("target_label = \"service\"");

		Properties datasource = loadYaml(GRAFANA_DATASOURCES);
		assertThat(datasource.getProperty("datasources[0].name")).isEqualTo("Prometheus");
		assertThat(datasource.getProperty("datasources[0].type")).isEqualTo("prometheus");
		assertThat(datasource.getProperty("datasources[0].url")).isEqualTo("http://prometheus:9090");
		assertThat(datasource.getProperty("datasources[1].name")).isEqualTo("Loki");
		assertThat(datasource.getProperty("datasources[1].type")).isEqualTo("loki");
		assertThat(datasource.getProperty("datasources[1].url")).isEqualTo("http://loki:3100");

		Properties dashboardProvider = loadYaml(GRAFANA_DASHBOARDS);
		assertThat(dashboardProvider.getProperty("providers[0].name")).isEqualTo("Home Search");
		assertThat(dashboardProvider.getProperty("providers[0].options.path"))
			.isEqualTo("/var/lib/grafana/dashboards");
	}

	@Test
	@DisplayName("local Grafana dashboard는 Home Search 핵심 metric과 API log query를 포함한다")
	void localGrafanaDashboardContainsHomeSearchQueries() throws IOException {
		assertThat(GRAFANA_HOME_SEARCH_DASHBOARD).exists();

		String dashboard = Files.readString(GRAFANA_HOME_SEARCH_DASHBOARD);
		assertThat(dashboard).contains("Home Search Local Overview");
		assertThat(dashboard).contains("home_search_ingest_items_total");
		assertThat(dashboard).contains("home_search_map_requests_total");
		assertThat(dashboard).contains("home_search_map_marker_cache_requests_total");
		assertThat(dashboard).contains("http_server_requests_seconds");
		assertThat(dashboard).contains("jvm_memory_used_bytes");
		String apiServiceSelector = "{service=" + "\\\"" + "api" + "\\\"" + "}";
		assertThat(dashboard).contains(apiServiceSelector);
		assertThat(dashboard).contains(apiServiceSelector + " |= " + "\\\"" + "ERROR" + "\\\"");
		assertThat(dashboard).doesNotContain("source_key");
		assertThat(dashboard).doesNotContain("APT_SERVICE_KEY");
		assertThat(dashboard).doesNotContain("raw_provider_payload");
	}

	private Properties loadYaml(Path path) {
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new FileSystemResource(path));
		return factory.getObject();
	}
}
