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
	private static final Path DOMAIN_ROOT = Path.of("src/main/java/com/home/domain");

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
	@DisplayName("domain layer는 application, infrastructure, Spring, JDBC를 import하지 않는다")
	void domainLayerStaysIndependentFromOuterLayersAndFrameworks() throws IOException {
		assertThat(Files.isDirectory(DOMAIN_ROOT))
			.as("domain package is missing")
			.isTrue();

		List<String> violations;
		try (var paths = Files.walk(DOMAIN_ROOT)) {
			violations = paths
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.flatMap(BackendPackageBoundaryTest::domainForbiddenImports)
				.toList();
		}

		assertThat(violations).isEmpty();
	}

	private static java.util.stream.Stream<String> infrastructureImports(Path path) {
		try {
			return Files.readAllLines(path)
				.stream()
				.filter(line -> line.contains("import com.home.infrastructure."))
				.map(line -> APPLICATION_ROOT.relativize(path) + ": " + line.trim());
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect source file: " + path, ex);
		}
	}

	private static java.util.stream.Stream<String> domainForbiddenImports(Path path) {
		try {
			return Files.readAllLines(path)
				.stream()
				.filter(BackendPackageBoundaryTest::isDomainForbiddenImport)
				.map(line -> DOMAIN_ROOT.relativize(path) + ": " + line.trim());
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect source file: " + path, ex);
		}
	}

	private static boolean isDomainForbiddenImport(String line) {
		return line.contains("import com.home.application.")
			|| line.contains("import com.home.infrastructure.")
			|| line.contains("import org.springframework.")
			|| line.contains("import org.flywaydb.")
			|| line.contains("import java.sql.")
			|| line.contains("import javax.sql.");
	}
}
