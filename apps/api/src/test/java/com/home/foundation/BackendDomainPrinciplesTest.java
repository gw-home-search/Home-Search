package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendDomainPrinciplesTest {

	private static final Path ROOT_AGENTS = Path.of("../..", "AGENTS.md");

	@Test
	@DisplayName("root AGENTS는 backend code addition에 적용되는 Domain Principles를 고정한다")
	void rootAgentsDocumentsDomainPrinciplesForBackendCodeAdditions() throws IOException {
		String agents = Files.readString(ROOT_AGENTS);

		assertThat(agents).contains("## Domain Principles");
		assertThat(agents).contains("These rules apply to every backend code addition");
		assertThat(agents).contains("com.home.domain.<feature>");
		assertThat(agents).contains("Domain code must not import `application/**`, `infrastructure/**`");
		assertThat(agents).contains("Persistence adapters enforce database constraints");
	}
}
