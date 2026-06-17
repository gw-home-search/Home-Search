package com.home.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BackendRuntimeSplitOwnershipTest {

	private static final Path API_RTMS_SCHEDULING =
		Path.of("src/main/java/com/home/infrastructure/scheduling/rtms");
	private static final Path API_APPLICATION_YML = Path.of("src/main/resources/application.yml");
	private static final Path LOCAL_COMPOSE = Path.of("../../infra/docker-compose.local.yml");
	private static final Path RTMS_LOADER_ROOT = Path.of("../../apps/rtms-loader");
	private static final Path SOURCE_DATA_ROOT = Path.of("../../apps/source-data");
	private static final Path NEWS_ROOT = Path.of("../../apps/news");
	private static final Path RTMS_INGEST_CORE_ROOT = Path.of("../../libs/rtms-ingest-core");
	private static final Path API_APPLICATION_INGEST_TRADE =
		Path.of("src/main/java/com/home/application/ingest/trade");
	private static final Path API_BUILD_GRADLE = Path.of("build.gradle");

	@Test
	@DisplayName("API runtime은 RTMS daily refresh만 소유하고 one-shot 초기 적재 runner를 소유하지 않는다")
	void apiRuntimeOwnsDailyRefreshButNotOneShotInitialLoader() throws IOException {
		assertThat(API_RTMS_SCHEDULING.resolve("RtmsDailyRefreshScheduler.java")).exists();
		assertThat(API_RTMS_SCHEDULING.resolve("RtmsMonthlyRefreshRunner.java")).exists();
		assertThat(API_RTMS_SCHEDULING.resolve("RtmsOneShotIngestApplicationRunner.java")).doesNotExist();
		assertThat(API_RTMS_SCHEDULING.resolve("RtmsOneShotTradeIngestRunner.java")).doesNotExist();
		assertThat(API_RTMS_SCHEDULING.resolve("RtmsOneShotIngestProperties.java")).doesNotExist();
		assertThat(API_RTMS_SCHEDULING.resolve("RtmsIngestMode.java")).doesNotExist();
	}

	@Test
	@DisplayName("split runtime app scaffold는 RTMS loader, source-data, news, ingest-core 경계를 제공한다")
	void splitRuntimeAppsProvideExplicitOwnershipBoundaries() {
		assertThat(RTMS_LOADER_ROOT.resolve("src/main/java/com/home/rtmsloader/RtmsLoaderApplication.java")).exists();
		assertThat(SOURCE_DATA_ROOT.resolve("src/main/java/com/home/sourcedata/SourceDataApplication.java")).exists();
		assertThat(NEWS_ROOT.resolve("src/main/java/com/home/news/NewsApplication.java")).exists();
		assertThat(RTMS_INGEST_CORE_ROOT.resolve("src/main/java/com/home/ingestcore/RtmsIngestCoreBoundary.java")).exists();
	}

	@Test
	@DisplayName("RTMS 순수 ingest 값과 정규화 규칙은 API 앱이 아니라 ingest-core가 소유한다")
	void rtmsPureIngestTypesBelongToSharedCore() throws IOException {
		String apiBuild = Files.readString(API_BUILD_GRADLE);
		Path coreRtmsPackage = RTMS_INGEST_CORE_ROOT.resolve("src/main/java/com/home/ingestcore/rtms");

		assertThat(apiBuild).contains("com.home:rtms-ingest-core");
		assertThat(coreRtmsPackage.resolve("OpenApiTradeItem.java")).exists();
		assertThat(coreRtmsPackage.resolve("ParsedRtmsTrade.java")).exists();
		assertThat(coreRtmsPackage.resolve("SourceKeyGenerator.java")).exists();
		assertThat(coreRtmsPackage.resolve("RtmsDealMonth.java")).exists();
		assertThat(coreRtmsPackage.resolve("RtmsLawdCode.java")).exists();
		assertThat(coreRtmsPackage.resolve("TradeExclAreaNormalizer.java")).exists();
		assertThat(API_APPLICATION_INGEST_TRADE.resolve("OpenApiTradeItem.java")).doesNotExist();
		assertThat(API_APPLICATION_INGEST_TRADE.resolve("ParsedRtmsTrade.java")).doesNotExist();
		assertThat(API_APPLICATION_INGEST_TRADE.resolve("SourceKeyGenerator.java")).doesNotExist();
	}

	@Test
	@DisplayName("API 설정과 local compose는 one-shot 초기 적재 env를 노출하지 않는다")
	void apiConfigurationDoesNotExposeOneShotInitialLoaderEnv() throws IOException {
		String application = Files.readString(API_APPLICATION_YML);
		String compose = Files.readString(LOCAL_COMPOSE);

		assertThat(application)
			.contains("daily:")
			.doesNotContain("mode: one-shot")
			.doesNotContain("lawd-cd:")
			.doesNotContain("deal-ymd:")
			.doesNotContain("page-no:")
			.doesNotContain("preflight-only:");
		assertThat(compose)
			.doesNotContain("HOME_INGEST_RTMS_ENABLED")
			.doesNotContain("HOME_INGEST_RTMS_MODE");
	}

	@Test
	@DisplayName("source-data app은 coordinate source schema와 VWorld WFS raw/cache migration을 소유한다")
	void sourceDataAppOwnsCoordinateSourceAndVworldRawCacheMigrations() {
		assertThat(SOURCE_DATA_ROOT.resolve("src/main/resources/db/migration/coordinate-source/V1__create_coordinate_source_schema.sql"))
			.exists();
		assertThat(SOURCE_DATA_ROOT.resolve("src/main/resources/db/migration/geo-enrichment/V1__create_geo_enrichment_schema.sql"))
			.exists();
		assertThat(Path.of("src/main/resources/db/migration/geo-enrichment/V1__create_geo_enrichment_schema.sql"))
			.doesNotExist();
	}
}
