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
	private static final Path COORDINATE_EXCEPTION_SERVICE =
		APPLICATION_ROOT.resolve("coordinate/caseflow/ComplexCoordinateExceptionService.java");
	private static final Path RTMS_MONTHLY_REFRESH_RUNNER =
		Path.of("src/main/java/com/home/infrastructure/scheduling/rtms/RtmsMonthlyRefreshRunner.java");
	private static final Path RTMS_EXTERNAL_API_CONFIGURATION =
		Path.of("src/main/java/com/home/infrastructure/external/rtms/RtmsExternalApiConfiguration.java");
	private static final Path RTMS_BATCH_ORCHESTRATION_CONFIGURATION =
		Path.of("src/main/java/com/home/infrastructure/scheduling/rtms/RtmsBatchOrchestrationConfiguration.java");
	private static final Path NAVER_NEWS_DAILY_PIPELINE_RUNNER =
		Path.of("src/main/java/com/home/infrastructure/scheduling/news/NaverNewsDailyPipelineRunner.java");
	private static final Path DATA_STORAGE_DOC = Path.of("../../docs/DATA_STORAGE.md");
	private static final List<Path> SCHEDULERS = List.of(
		Path.of("src/main/java/com/home/infrastructure/external/complex/ComplexMetadataEnrichmentScheduler.java"),
		Path.of("src/main/java/com/home/infrastructure/scheduling/news/NaverNewsDailyPipelineScheduler.java"),
		Path.of("src/main/java/com/home/infrastructure/scheduling/rtms/RtmsDailyRefreshScheduler.java"),
		Path.of("src/main/java/com/home/infrastructure/scheduling/coordinate/ComplexCoordinateReadinessScheduler.java")
	);
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

	@Test
	@DisplayName("coordinate caseflow와 RTMS monthly runner는 null 제어 흐름과 분기별 저장 조립을 사용하지 않는다")
	void orchestrationUsesExplicitResultsAndSingleRunPersistencePath() throws IOException {
		String coordinateService = Files.readString(COORDINATE_EXCEPTION_SERVICE);
		String monthlyRefreshRunner = Files.readString(RTMS_MONTHLY_REFRESH_RUNNER);

		assertThat(coordinateService).doesNotContain("return null;");
		assertThat(monthlyRefreshRunner.split("ingestRunRepository\\.save", -1)).hasSize(2);
	}

	@Test
	@DisplayName("scheduled 진입점은 공통 execution template으로 동시 실행을 방지한다")
	void scheduledEntrypointsUseCommonExecutionTemplate() throws IOException {
		for (Path scheduler : SCHEDULERS) {
			String source = Files.readString(scheduler);

			assertThat(source)
				.contains("ScheduledJobExecutionTemplate")
				.doesNotContain("AtomicBoolean");
		}
	}

	@Test
	@DisplayName("batch orchestration은 external adapter와 persistence 패키지 밖에 둔다")
	void batchOrchestrationLivesOutsideExternalAdaptersAndPersistence() throws IOException {
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/external/naver/NaverNewsDailyPipelineRunner.java"
		)).doesNotExist();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/persistence/coordinate/ComplexCoordinateReadinessScheduler.java"
		)).doesNotExist();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/scheduling/news/NaverNewsDailyPipelineRunner.java"
		)).exists();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/scheduling/coordinate/ComplexCoordinateReadinessScheduler.java"
		)).exists();
		assertThat(Files.readString(Path.of(
			"src/main/java/com/home/infrastructure/persistence/coordinate/ComplexCoordinatePersistenceConfiguration.java"
		))).doesNotContain("ComplexCoordinateReadinessScheduler");

		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/external/rtms/RtmsMonthlyRefreshRunner.java"
		)).doesNotExist();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/external/rtms/RtmsNationwideBackfillRunner.java"
		)).doesNotExist();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/external/rtms/RtmsDailyRefreshScheduler.java"
		)).doesNotExist();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/external/rtms/RtmsOneShotIngestApplicationRunner.java"
		)).doesNotExist();
		assertThat(RTMS_MONTHLY_REFRESH_RUNNER).exists();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/scheduling/rtms/RtmsNationwideBackfillRunner.java"
		)).exists();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/scheduling/rtms/RtmsDailyRefreshScheduler.java"
		)).exists();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/scheduling/rtms/RtmsOneShotIngestApplicationRunner.java"
		)).exists();
		assertThat(RTMS_BATCH_ORCHESTRATION_CONFIGURATION).exists();
		assertThat(Files.readString(RTMS_EXTERNAL_API_CONFIGURATION))
			.doesNotContain("RtmsMonthlyRefreshRunner")
			.doesNotContain("RtmsNationwideBackfillRunner")
			.doesNotContain("RtmsOneShotIngestApplicationRunner");
	}

	@Test
	@DisplayName("RTMS ingest 정책 플래그 바인딩과 호출 간격 제어는 단일 지점에 둔다")
	void rtmsIngestFlagBindingAndCallPacingHaveSingleOwners() throws IOException {
		assertThat(Files.readString(RTMS_EXTERNAL_API_CONFIGURATION))
			.doesNotContain("allow-coordinate-pending-only")
			.contains("RateLimitedRtmsApartmentTradeClient")
			.contains("min-request-interval-millis");
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/external/rtms/RtmsCoordinateSourcePreflight.java"
		)).doesNotExist();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/external/rtms/RequiredRtmsCoordinateSourcePreflight.java"
		)).doesNotExist();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/scheduling/rtms/RtmsCoordinateSourcePreflight.java"
		)).exists();
		assertThat(Files.readString(RTMS_BATCH_ORCHESTRATION_CONFIGURATION))
			.contains("rtmsCoordinateSourcePreflight");
	}

	@Test
	@DisplayName("application runner 순서는 선행 단계 의존성을 명시하고 전용 supplier wrapper를 만들지 않는다")
	void applicationRunnerOrderingIsExplicitWithoutDedicatedReferenceWrappers() throws IOException {
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/external/rtms/RtmsTradeIngestServiceReference.java"
		)).doesNotExist();
		assertThat(Path.of(
			"src/main/java/com/home/infrastructure/external/rtms/RtmsIngestRunRepositoryReference.java"
		)).doesNotExist();

		String orders = Files.readString(Path.of(
			"src/main/java/com/home/infrastructure/ApplicationRunnerOrders.java"
		));
		assertThat(orders)
			.contains("RAW_INGEST_RECONCILIATION = RTMS_ONE_SHOT_INGEST + INGEST_PHASE_STEP")
			.contains("COORDINATE_READINESS = RAW_INGEST_RECONCILIATION + INGEST_PHASE_STEP")
			.contains("NEWS_RELEVANCE_GATE = NEWS_ONE_SHOT_INGEST + NEWS_COLLECTION_STEP")
			.contains("NEWS_SIGNAL_FEATURE_EXTRACTION = NEWS_RELEVANCE_GATE + NEWS_PROCESSING_STEP")
			.contains("NEWS_OBSERVATION_CLEANUP = NEWS_SIGNAL_FEATURE_EXTRACTION + NEWS_PROCESSING_STEP")
			.contains("NEWS_OBSIDIAN_EXPORT = NEWS_OBSERVATION_CLEANUP + NEWS_PROCESSING_STEP");
	}

	@Test
	@DisplayName("data storage 문서는 ingest의 의도된 cross-repository transaction 경계와 복구 한계를 설명한다")
	void dataStorageDocumentsIngestTransactionBoundaryAndRecoveryLimits() throws IOException {
		String dataStorage = Files.readString(DATA_STORAGE_DOC);

		assertThat(dataStorage)
			.contains("## Ingest Transaction Boundary And Recovery")
			.contains("cross-repository transaction")
			.contains("raw_trade_ingest")
			.contains("JdbcNormalizedTradeRepository")
			.contains("RawIngestReconciliationRunner")
			.contains("RECEIVED")
			.contains("idempotent")
			.contains("does not repair every partial state");
	}

	@Test
	@DisplayName("RTMS monthly 상태 변환은 status별 평행 switch 대신 단일 팩토리를 사용한다")
	void rtmsMonthlyStatusConversionsUseSingleFactories() throws IOException {
		String runner = Files.readString(RTMS_MONTHLY_REFRESH_RUNNER);
		String configuration = Files.readString(RTMS_BATCH_ORCHESTRATION_CONFIGURATION);

		assertThat(runner)
			.contains("RtmsIngestRunRecord.of(")
			.contains("RtmsMonthlyRefreshRunSummary.of(")
			.doesNotContain("switch (status)");
		assertThat(configuration)
			.contains("RtmsBackfillChunkExecutionResult.from(")
			.doesNotContain("summaryToBackfillResult");
	}

	@Test
	@DisplayName("persisted matching path와 backfill source 값은 domain value로 정의한다")
	void persistedMatchingAndBackfillEvidenceUsesDomainValues() throws IOException {
		assertThat(Path.of(
			"src/main/java/com/home/domain/ingest/matching/TradeMatchPath.java"
		)).exists();
		assertThat(Path.of(
			"src/main/java/com/home/domain/ingest/backfill/RtmsBackfillLawdCodeSource.java"
		)).exists();

		String matchPolicy = Files.readString(Path.of(
			"src/main/java/com/home/application/ingest/matching/ComplexMatchCandidatePolicy.java"
		));
		String backfillRunner = Files.readString(Path.of(
			"src/main/java/com/home/infrastructure/scheduling/rtms/RtmsNationwideBackfillRunner.java"
		));
		assertThat(matchPolicy)
			.doesNotContain("\"PNU_NAME\"", "\"PNU_ALIAS_NAME\"", "\"APTSEQ\"", "\"PNU_UNIQUE\"");
		assertThat(backfillRunner)
			.doesNotContain("\"RTMS\"", "\"region.si-gun-gu\"");
	}

	@Test
	@DisplayName("news completion과 RTMS 설정은 의미 단위 객체로 조립한다")
	void largePositionalAssemblyUsesSemanticGroups() throws IOException {
		String newsRunner = Files.readString(NAVER_NEWS_DAILY_PIPELINE_RUNNER);
		String rtmsConfiguration = Files.readString(RTMS_BATCH_ORCHESTRATION_CONFIGURATION);

		assertThat(newsRunner)
			.contains("NewsCollectionRunCompletion.from(")
			.doesNotContain("new NewsCollectionRunCompletion(");
		assertThat(rtmsConfiguration)
			.contains("RtmsOneShotIngestConfigurationProperties")
			.doesNotContain("@Value(\"${home.ingest.rtms.enabled:");
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
