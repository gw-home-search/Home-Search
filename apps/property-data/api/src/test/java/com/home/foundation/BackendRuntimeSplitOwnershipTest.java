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
		"HOME_INGEST_RTMS_MODE"
	);

	@Test
	@DisplayName("API runtime은 RTMS daily refresh만 소유하고 one-shot 초기 적재 runner를 소유하지 않는다")
	void apiRuntimeOwnsDailyRefreshButNotOneShotInitialLoader() throws IOException {
		assertThat(API_RTMS_SCHEDULING.resolve("RtmsDailyRefreshScheduler.java")).exists();
		assertThat(API_RTMS_SCHEDULING.resolve("RtmsMonthlyRefreshRunner.java")).exists();
		assertThat(filesUnder(API_RTMS_SCHEDULING))
			.noneMatch(path -> path.getFileName().toString().contains("OneShot"));
	}

	@Test
	@DisplayName("API 설정과 local compose는 one-shot 초기 적재 env를 노출하지 않는다")
	void apiConfigurationDoesNotExposeOneShotInitialLoaderEnv() throws IOException {
		String application = Files.readString(API_APPLICATION_YML);
		String compose = Files.readString(LOCAL_COMPOSE);

		assertThat(application).contains("daily:");
		for (String token : ONE_SHOT_TOKENS) {
			assertThat(application).doesNotContain(token);
			assertThat(compose).doesNotContain(token);
		}
	}

	@Test
	@DisplayName("runtime split은 core/api만 생성하고 batch-app과 신규 libs는 만들지 않는다")
	void runtimeSplitCreatesCoreAndApiAppOnly() throws IOException {
		String plan = Files.readString(RESTRUCTURING_PLAN);

		assertThat(plan)
			.contains("`core` / `api` / `batch-app`")
			.contains("신규 라이브러리는 §3.3 승격 조건 충족 시에만");
		assertThat(HOME_DATA_ROOT.resolve("core")).isDirectory();
		assertThat(HOME_DATA_ROOT.resolve("api")).isDirectory();
		assertThat(HOME_DATA_ROOT.resolve("batch-app")).doesNotExist();
		assertThat(LIBS_ROOT.resolve("rtms-ingest-core")).exists();
		assertThat(LIBS_ROOT.resolve("geo-core")).doesNotExist();
	}

	private static List<Path> filesUnder(Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			return paths
				.filter(Files::isRegularFile)
				.toList();
		}
	}
}
