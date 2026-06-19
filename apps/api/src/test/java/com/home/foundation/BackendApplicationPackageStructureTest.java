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
	private static final Path PRODUCTION_ROOT = Path.of("src/main/java/com/home");
	private static final Path COMPLEX_MATCH_CANDIDATE_POLICY =
		APPLICATION_ROOT.resolve("ingest/matching/ComplexMatchCandidatePolicy.java");
	private static final Set<String> FORBIDDEN_ROLE_PACKAGES = Set.of("common", "dto", "model", "service", "util");

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

	@Test
	@DisplayName("scheduled 진입점은 공통 execution template으로 재진입을 방지한다")
	void scheduledEntrypointsUseCommonExecutionTemplate() throws IOException {
		List<String> violations;
		try (var paths = Files.walk(PRODUCTION_ROOT)) {
			violations = paths
				.filter(Files::isRegularFile)
				.filter(path -> path.toString().endsWith(".java"))
				.filter(BackendApplicationPackageStructureTest::containsScheduledAnnotation)
				.flatMap(BackendApplicationPackageStructureTest::scheduledEntrypointViolations)
				.toList();
		}

		assertThat(violations).isEmpty();
	}

	@Test
	@DisplayName("persisted matching path 값은 domain value로 정의한다")
	void persistedMatchingPathUsesDomainValues() throws IOException {
		assertThat(Path.of(
			"src/main/java/com/home/domain/ingest/matching/TradeMatchPath.java"
		)).exists();
		assertThat(Path.of(
			"src/main/java/com/home/domain/ingest/backfill/RtmsBackfillLawdCodeSource.java"
		)).doesNotExist();

		String matchPolicy = Files.readString(COMPLEX_MATCH_CANDIDATE_POLICY);
		assertThat(matchPolicy)
			.contains("TradeMatchPath.")
			.doesNotContain("\"PNU_NAME\"", "\"PNU_ALIAS_NAME\"", "\"APTSEQ\"", "\"PNU_UNIQUE\"");
	}

	private static boolean containsScheduledAnnotation(Path path) {
		try {
			return Files.readString(path).contains("@Scheduled");
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect source file: " + path, ex);
		}
	}

	private static java.util.stream.Stream<String> scheduledEntrypointViolations(Path path) {
		try {
			String source = Files.readString(path);
			Path relativePath = PRODUCTION_ROOT.relativize(path);
			var violations = new java.util.ArrayList<String>();
			if (!source.contains("ScheduledJobExecutionTemplate")) {
				violations.add(relativePath + ": ScheduledJobExecutionTemplate is missing");
			}
			if (source.contains("AtomicBoolean")) {
				violations.add(relativePath + ": owns a local AtomicBoolean guard");
			}
			return violations.stream();
		} catch (IOException ex) {
			throw new IllegalStateException("Failed to inspect source file: " + path, ex);
		}
	}

	private static boolean isForbiddenRolePackage(Path path) {
		return FORBIDDEN_ROLE_PACKAGES.contains(path.getFileName().toString());
	}
}
