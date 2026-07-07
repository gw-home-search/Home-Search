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
	private static final Path GLOBAL_ROOT = Path.of("../api/src/main/java/com/home/global");
	private static final Path PRODUCTION_ROOT = Path.of("src/main/java/com/home");
	private static final Path API_PRODUCTION_ROOT = Path.of("../api/src/main/java/com/home");
	private static final Path INFRASTRUCTURE_EXTERNAL_ROOT = Path.of("src/main/java/com/home/infrastructure/external");
	private static final Path BUILD_GRADLE = Path.of("build.gradle");
	private static final Path API_APP_BUILD_GRADLE = Path.of("../api/build.gradle");
	private static final Path APPLICATION_YML = Path.of("../api/src/main/resources/application.yml");

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

	@Test
	@DisplayName("global layer는 infrastructure package를 import하지 않는다")
	void globalLayerDoesNotImportInfrastructurePackages() throws IOException {
		List<String> violations;
		try (var paths = Files.walk(GLOBAL_ROOT)) {
			violations = paths
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.flatMap(path -> matchingImports(path, GLOBAL_ROOT, "import com.home.infrastructure."))
				.toList();
		}

		assertThat(violations).isEmpty();
	}

	@Test
	@DisplayName("external adapter는 persistence adapter package를 import하지 않는다")
	void externalAdaptersDoNotImportPersistenceAdapters() throws IOException {
		List<String> violations;
		try (var paths = Files.walk(INFRASTRUCTURE_EXTERNAL_ROOT)) {
			violations = paths
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.flatMap(path -> matchingImports(path, INFRASTRUCTURE_EXTERNAL_ROOT, "import com.home.infrastructure.persistence."))
				.toList();
		}

		assertThat(violations).isEmpty();
	}

	@Test
	@DisplayName("backend production read path는 JDBC 하나만 사용하고 JPA Hibernate Lombok에 의존하지 않는다")
	void productionReadPathUsesOnlyJdbcWithoutJpaHibernateOrLombok() throws IOException {
		List<String> violations = java.util.stream.Stream.of(PRODUCTION_ROOT, API_PRODUCTION_ROOT)
			.flatMap(BackendPackageBoundaryTest::javaFilesUnder)
			.flatMap(path -> java.util.stream.Stream.of(
					"import jakarta.persistence.",
					"import org.hibernate.",
					"import lombok."
				)
				.flatMap(forbiddenImport -> matchingImports(path, moduleRoot(path), forbiddenImport)))
			.toList();

		assertThat(violations).isEmpty();
		assertThat(Files.readString(BUILD_GRADLE))
			.doesNotContain("spring-boot-starter-data-jpa")
			.doesNotContain("org.projectlombok:lombok");
		assertThat(Files.readString(API_APP_BUILD_GRADLE))
			.doesNotContain("spring-boot-starter-data-jpa")
			.doesNotContain("org.projectlombok:lombok");
		assertThat(Files.readString(APPLICATION_YML)).doesNotContain("\n  jpa:");
	}

	private static java.util.stream.Stream<Path> javaFilesUnder(Path root) {
		try {
			return Files.walk(root)
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"));
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect source directory: " + root, ex);
		}
	}

	private static Path moduleRoot(Path path) {
		return path.startsWith(API_PRODUCTION_ROOT) ? API_PRODUCTION_ROOT : PRODUCTION_ROOT;
	}

	private static java.util.stream.Stream<String> infrastructureImports(Path path) {
		return matchingImports(path, "import com.home.infrastructure.");
	}

	private static java.util.stream.Stream<String> matchingImports(Path path, String forbiddenImport) {
		return matchingImports(path, APPLICATION_ROOT, forbiddenImport);
	}

	private static java.util.stream.Stream<String> matchingImports(Path path, Path root, String forbiddenImport) {
		try {
			return Files.readAllLines(path)
				.stream()
				.filter(line -> line.contains(forbiddenImport))
				.map(line -> root.relativize(path) + ": " + line.trim());
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect source file: " + path, ex);
		}
	}
}
