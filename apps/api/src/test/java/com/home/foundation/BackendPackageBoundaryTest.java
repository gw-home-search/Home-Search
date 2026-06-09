package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendPackageBoundaryTest {

	private static final Path APPLICATION_ROOT = Path.of("src/main/java/com/home/application");

	@Test
	@DisplayName("application layer는 infrastructure package를 import하지 않는다")
	void applicationLayerDoesNotImportInfrastructurePackages() throws IOException {
		List<String> violations;
		try (var paths = Files.walk(APPLICATION_ROOT)) {
			violations = paths
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.flatMap(BackendPackageBoundaryTest::infrastructureImports)
				.toList();
		}

		assertThat(violations).isEmpty();
	}

	@Test
	@DisplayName("application layer는 Spring framework를 import하지 않는다")
	void applicationLayerDoesNotImportSpringFramework() throws IOException {
		List<String> violations;
		try (var paths = Files.walk(APPLICATION_ROOT)) {
			violations = paths
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.flatMap(path -> matchingImports(path, "import org.springframework."))
				.toList();
		}

		assertThat(violations).isEmpty();
	}

	private static java.util.stream.Stream<String> infrastructureImports(Path path) {
		return matchingImports(path, "import com.home.infrastructure.");
	}

	private static java.util.stream.Stream<String> matchingImports(Path path, String forbiddenImport) {
		try {
			return Files.readAllLines(path)
				.stream()
				.filter(line -> line.contains(forbiddenImport))
				.map(line -> APPLICATION_ROOT.relativize(path) + ": " + line.trim());
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect source file: " + path, ex);
		}
	}
}
