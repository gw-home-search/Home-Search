package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendQualityGateConfigurationTest {

	private static final Path QUALITY_GATE = Path.of("backend-quality-gate.toml");
	private static final Path BUILD_GRADLE = Path.of("build.gradle");

	@Test
	@DisplayName("quality gate는 90 percent coverage, OpenAPI path, forbidden field, command를 기록한다")
	void qualityGateDocumentsBackendQualityRequirements() throws IOException {
		String content = Files.readString(QUALITY_GATE);
		String buildGradle = Files.readString(BUILD_GRADLE);

		assertThat(content).contains("line_minimum = 0.90");
		assertThat(content).contains("instruction_minimum = 0.90");
		assertThat(content).contains("branch_minimum = 0.65");
		assertThat(buildGradle).contains("counter = 'BRANCH'");
		assertThat(buildGradle).contains("minimum = 0.65");
		assertThat(content).contains("/api/v1/map/regions");
		assertThat(content).contains("/api/v1/map/complexes");
		assertThat(content).contains("complexPk");
		assertThat(content).contains("sourceKey");
		assertThat(content).contains("backendQualityCheck");
		assertThat(content).contains("apiDocsCheck");
		assertThat(content).contains("coverageCheck");
	}
}
