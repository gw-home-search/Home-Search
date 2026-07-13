package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendRuntimeSplitOwnershipTest {

	private static final Path API_RTMS_SCHEDULING =
		Path.of("src/main/java/com/home/infrastructure/scheduling/rtms");
	private static final Path API_APPLICATION_YML = Path.of("src/main/resources/application.yml");
	private static final Path HOME_DATA_ROOT = Path.of("..");
	private static final Path RESTRUCTURING_PLAN = Path.of("..", "..", "..", "docs", "RESTRUCTURING_PLAN.md");
	private static final Path LIBS_ROOT = Path.of("..", "..", "..", "libs");
	private static final Path LOCAL_COMPOSE = Path.of("..", "..", "..", "infra", "docker-compose.local.yml");
	private static final List<String> ONE_SHOT_TOKENS = List.of(
		"RtmsOneShot",
		"mode: one-shot",
		"HOME_INGEST_RTMS_ENABLED",
		"HOME_INGEST_RTMS_MODE",
		"HOME_INGEST_RTMS_DAILY_ENABLED",
		"HOME_INGEST_RTMS_DAILY_CRON"
	);

	@Test
	@DisplayName("API runtime은 RTMS scheduler와 one-shot 초기 적재 runner를 소유하지 않는다")
	void apiRuntimeDoesNotOwnRtmsScheduledOrOneShotExecution() throws IOException {
		assertThat(API_RTMS_SCHEDULING.resolve("RtmsDailyRefreshScheduler.java")).doesNotExist();
		assertThat(filesUnder(API_RTMS_SCHEDULING))
			.noneMatch(path -> path.getFileName().toString().contains("OneShot"));
	}

	@Test
	@DisplayName("clean checkout에서 삭제된 runtime 디렉터리는 빈 파일 목록으로 취급한다")
	void missingRuntimeDirectoryIsTreatedAsEmpty() throws IOException {
		assertThat(filesUnder(Path.of("build", "missing-runtime-directory"))).isEmpty();
	}

	@Test
	@DisplayName("API 설정과 local compose는 legacy RTMS 실행 env를 노출하지 않는다")
	void apiConfigurationDoesNotExposeLegacyRtmsExecutionEnv() throws IOException {
		String application = Files.readString(API_APPLICATION_YML);
		String compose = Files.readString(LOCAL_COMPOSE);

		for (String token : ONE_SHOT_TOKENS) {
			assertThat(application).doesNotContain(token);
			assertThat(compose).doesNotContain(token);
		}
	}

	@Test
	@DisplayName("Stage 1 runtime split은 core/api/batch/migration 경계를 유지한다")
	void runtimeSplitCreatesCoreApiAndBatchAppOnly() throws IOException {
		String plan = Files.readString(RESTRUCTURING_PLAN);

		assertThat(plan)
			.contains("상태: Stage 1 완료")
			.contains("├── core/")
			.contains("├── api/")
			.contains("├── batch/")
			.contains("└── migration/")
			.contains("user-service는 property-data 내부 module이");
		assertThat(HOME_DATA_ROOT.resolve("core")).isDirectory();
		assertThat(HOME_DATA_ROOT.resolve("api")).isDirectory();
		assertThat(HOME_DATA_ROOT.resolve("batch")).isDirectory();
		assertThat(HOME_DATA_ROOT.resolve("migration")).isDirectory();
		assertThat(LIBS_ROOT.resolve("rtms-ingest-core")).exists();
		assertThat(LIBS_ROOT.resolve("geo-core")).doesNotExist();
	}

	private static List<Path> filesUnder(Path root) throws IOException {
		if (Files.notExists(root)) {
			return List.of();
		}
		try (var paths = Files.walk(root)) {
			return paths
				.filter(Files::isRegularFile)
				.toList();
		}
	}
}
