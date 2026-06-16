package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunnerLifecycleDocumentationTest {

	private static final Path RUNNER_DOC = Path.of("RUNNER_LIFECYCLE.md");
	private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java/com/home");
	private static final Pattern DOCUMENTED_CLASS = Pattern.compile("^\\| `(?<className>[^`]+)` \\|");

	@Test
	@DisplayName("runner lifecycle 문서는 모든 Runner와 Scheduler class를 분류한다")
	void runnerLifecycleDocumentClassifiesEveryRunnerAndScheduler() throws IOException {
		Set<String> productionRunnerClasses = productionRunnerAndSchedulerClasses();
		Set<String> documentedRunnerClasses = documentedRunnerClasses();

		assertThat(documentedRunnerClasses).containsExactlyInAnyOrderElementsOf(productionRunnerClasses);
	}

	@Test
	@DisplayName("runner lifecycle 문서는 복구 runner와 백필 폐쇄 후보를 구분한다")
	void runnerLifecycleDocumentSeparatesRecoveryRunnersFromBackfillClosureCandidate() throws IOException {
		String document = Files.readString(RUNNER_DOC);

		assertThat(document)
			.contains("| `RawIngestReconciliationRunner` | maintenance |")
			.contains("| `TradePartitionMaintenanceRunner` | maintenance |")
			.contains("| `RtmsNationwideBackfillRunner` | removal-candidate |")
			.contains("`rtms_backfill_job`")
			.contains("public API URL, response shape, DB migration, ingest 정책 변경은 이 문서의");
	}

	private Set<String> productionRunnerAndSchedulerClasses() throws IOException {
		try (Stream<Path> paths = Files.walk(MAIN_SOURCE_ROOT)) {
			return paths
				.filter(Files::isRegularFile)
				.map(Path::getFileName)
				.map(Path::toString)
				.filter(name -> name.endsWith("Runner.java") || name.endsWith("Scheduler.java"))
				.map(name -> name.substring(0, name.length() - ".java".length()))
				.collect(Collectors.toSet());
		}
	}

	private Set<String> documentedRunnerClasses() throws IOException {
		try (Stream<String> lines = Files.lines(RUNNER_DOC)) {
			return lines
				.map(DOCUMENTED_CLASS::matcher)
				.filter(Matcher::find)
				.map(matcher -> matcher.group("className"))
				.collect(Collectors.toSet());
		}
	}
}
