package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalRuntimeStackConfigurationTest {

	private static final Path LOCAL_COMPOSE = Path.of("../../infra/docker-compose.local.yml");

	@Test
	@DisplayName("local compose stack wires PostGIS, API, Web, and V1 local seed without secrets")
	void localComposeStackWiresPostgisApiWebAndSeed() throws IOException {
		assertThat(LOCAL_COMPOSE).exists();

		String content = Files.readString(LOCAL_COMPOSE);

		assertThat(content).contains("postgis/postgis:16-3.4");
		assertThat(content).contains("DB_JDBC_URL: jdbc:postgresql://postgis:5432/${HOME_SEARCH_DB_NAME:-home_search}");
		assertThat(content).contains("SPRING_FLYWAY_LOCATIONS: classpath:db/migration/api,classpath:db/seed/local");
		assertThat(content).contains("VITE_API_SERVER_IP: ${VITE_API_SERVER_IP:-http://localhost:8080}");
		assertThat(content).doesNotContain("APT_SERVICE_KEY");
		assertThat(content).doesNotContain(".env");
	}
}
