package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendQualityGateConfigurationTest {

	private static final Path QUALITY_GATE = Path.of("backend-quality-gate.toml");

	@Test
	@DisplayName("quality gate records 90 percent coverage, OpenAPI paths, forbidden fields, and commands")
	void qualityGateDocumentsBackendQualityRequirements() throws IOException {
		String content = Files.readString(QUALITY_GATE);

		assertThat(content).contains("line_minimum = 0.90");
		assertThat(content).contains("instruction_minimum = 0.90");
		assertThat(content).contains("/api/v1/map/regions");
		assertThat(content).contains("/api/v1/map/complexes");
		assertThat(content).contains("complexPk");
		assertThat(content).contains("sourceKey");
		assertThat(content).contains("backendQualityCheck");
		assertThat(content).contains("apiDocsCheck");
		assertThat(content).contains("coverageCheck");
	}
}
