package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendApplicationPackageStructureTest {

	private static final Path APPLICATION_ROOT = Path.of("src/main/java/com/home/application");
	private static final Set<String> SPLIT_REQUIRED_FEATURES = Set.of("coordinate", "ingest", "news");
	private static final Set<String> FORBIDDEN_ROLE_PACKAGES = Set.of("common", "dto", "model", "service", "util");
	private static final int MAX_ROOT_CLASSES_FOR_SPLIT_FEATURE = 3;

	@Test
	@DisplayName("큰 application feature는 capability package로 분리된다")
	void largeApplicationFeaturesAreSplitByCapabilityPackage() throws IOException {
		List<String> violations = SPLIT_REQUIRED_FEATURES.stream()
			.flatMap(BackendApplicationPackageStructureTest::featureSplitViolations)
			.toList();

		assertThat(violations).isEmpty();
	}

	@Test
	@DisplayName("application 하위 package는 역할명 대신 capability 이름을 사용한다")
	void applicationSubpackagesUseCapabilityNamesInsteadOfRoleNames() throws IOException {
		List<String> violations;
		try (var paths = Files.walk(APPLICATION_ROOT)) {
			violations = paths
				.filter(Files::isDirectory)
				.filter(path -> !path.equals(APPLICATION_ROOT))
				.filter(BackendApplicationPackageStructureTest::isForbiddenRolePackage)
				.map(path -> APPLICATION_ROOT.relativize(path).toString())
				.toList();
		}

		assertThat(violations).isEmpty();
	}

	private static java.util.stream.Stream<String> featureSplitViolations(String feature) {
		Path featureRoot = APPLICATION_ROOT.resolve(feature);
		try {
			if (!Files.isDirectory(featureRoot)) {
				return java.util.stream.Stream.of(feature + ": feature package is missing");
			}
			long rootClassCount;
			try (var rootFiles = Files.list(featureRoot)) {
				rootClassCount = rootFiles
					.filter(Files::isRegularFile)
					.filter(path -> path.toString().endsWith(".java"))
					.count();
			}
			long subpackageCount;
			try (var rootEntries = Files.list(featureRoot)) {
				subpackageCount = rootEntries.filter(Files::isDirectory).count();
			}
			var violations = new java.util.ArrayList<String>();
			if (rootClassCount > MAX_ROOT_CLASSES_FOR_SPLIT_FEATURE) {
				violations.add(feature + ": root package has " + rootClassCount + " classes");
			}
			if (subpackageCount < 2) {
				violations.add(feature + ": capability subpackages are missing");
			}
			return violations.stream();
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect application feature package: " + featureRoot, ex);
		}
	}

	private static boolean isForbiddenRolePackage(Path path) {
		return FORBIDDEN_ROLE_PACKAGES.contains(path.getFileName().toString());
	}
}
