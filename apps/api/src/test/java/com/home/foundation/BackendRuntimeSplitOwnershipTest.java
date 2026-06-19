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
	private static final Path LOCAL_COMPOSE = Path.of("../../infra/docker-compose.local.yml");
	private static final Path RTMS_INGEST_CORE_ROOT = Path.of("../../libs/rtms-ingest-core");
	private static final Path API_APPLICATION_INGEST_TRADE =
		Path.of("src/main/java/com/home/application/ingest/trade");
	private static final Path API_BUILD_GRADLE = Path.of("build.gradle");
	private static final Path API_SETTINGS_GRADLE = Path.of("settings.gradle");
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
	@DisplayName("RTMS 순수 ingest 값과 정규화 규칙은 API 앱이 아니라 ingest-core가 소유한다")
	void rtmsPureIngestTypesBelongToSharedCore() throws IOException {
		String apiBuild = Files.readString(API_BUILD_GRADLE);
		String apiSettings = Files.readString(API_SETTINGS_GRADLE);
		Path coreRtmsPackage = RTMS_INGEST_CORE_ROOT.resolve("src/main/java/com/home/ingestcore/rtms");

		assertThat(apiBuild).contains("com.home:rtms-ingest-core");
		assertThat(apiSettings).contains("includeBuild('../../libs/rtms-ingest-core')");
		assertThat(coreRtmsPackage.resolve("OpenApiTradeItem.java")).exists();
		assertThat(coreRtmsPackage.resolve("ParsedRtmsTrade.java")).exists();
		assertThat(coreRtmsPackage.resolve("SourceKeyGenerator.java")).exists();
		assertThat(API_APPLICATION_INGEST_TRADE.resolve("OpenApiTradeItem.java")).doesNotExist();
		assertThat(API_APPLICATION_INGEST_TRADE.resolve("ParsedRtmsTrade.java")).doesNotExist();
		assertThat(API_APPLICATION_INGEST_TRADE.resolve("SourceKeyGenerator.java")).doesNotExist();
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

	private static List<Path> filesUnder(Path root) throws IOException {
		try (var paths = Files.walk(root)) {
			return paths
				.filter(Files::isRegularFile)
				.toList();
		}
	}
}
