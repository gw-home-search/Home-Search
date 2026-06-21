package com.home.news.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.home.domain.news.CollectionRunStatus;
import com.home.domain.news.NewsSource;
import com.home.domain.news.SignalEvidenceLevel;
import com.home.domain.news.SignalImpactDirection;
import com.home.domain.news.SignalImpactTarget;
import com.home.domain.news.SignalSentiment;
import com.home.news.NewsRuntimeProperties;
import com.home.news.infrastructure.persistence.JdbcNewsPostgresTestSupport;
import com.home.news.infrastructure.persistence.JdbcNewsRepository;
import com.home.news.infrastructure.runner.RunOnceNewsApplicationRunner;
import com.home.news.support.TextDigests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class OneKeywordNewsCollectionServiceTest extends JdbcNewsPostgresTestSupport {

	private static final PostgreSQLContainer<?> POSTGRES = newPostgisContainer();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private JdbcNewsRepository repository;
	private NewsRuntimeProperties properties;

	static {
		POSTGRES.start();
	}

	@BeforeEach
	void setUp() {
		initializeJdbc(POSTGRES);
		newsFlyway().clean();
		newsFlyway().migrate();
		repository = new JdbcNewsRepository(jdbcClient);
		properties = properties();
	}

	@Test
	@DisplayName("fake one-keyword E2E는 observation과 feature를 하나씩 생성한다")
	void runOnceCreatesObservationAndFeature() {
		OneKeywordNewsCollectionService service = service();

		CollectionRunCounts counts = service.collect("강남 재건축");

		assertThat(counts.status()).isEqualTo(CollectionRunStatus.SUCCEEDED);
		assertThat(counts.observedNewCount()).isEqualTo(1);
		assertThat(counts.featureCreatedCount()).isEqualTo(1);
		assertThat(count("news.article_observation")).isEqualTo(1);
		assertThat(count("news.signal_feature")).isEqualTo(1);
		assertThat(count("news.collection_run_article")).isEqualTo(1);
		assertThat(observationDatasetMetadata())
			.isEqualTo("PROVIDER_API|REALTIME_OBSERVED|SYSTEM_ACCEPTED|OBSERVED_SIGNAL");
	}

	@Test
	@DisplayName("rerun은 observation/feature 중복 없이 run provenance만 추가한다")
	void rerunKeepsObservationAndFeatureIdempotent() {
		OneKeywordNewsCollectionService service = service();

		service.collect("강남 재건축");
		CollectionRunCounts second = service.collect("강남 재건축");

		assertThat(second.observedDuplicateCount()).isEqualTo(1);
		assertThat(second.featureSkippedCount()).isEqualTo(1);
		assertThat(count("news.article_observation")).isEqualTo(1);
		assertThat(count("news.signal_feature")).isEqualTo(1);
		assertThat(count("news.collection_run")).isEqualTo(2);
		assertThat(count("news.collection_run_article")).isEqualTo(2);
	}

	@Test
	@DisplayName("OpenAI disabled이면 scorer 호출 없이 observation만 저장하고 feature를 생략한다")
	void openAiDisabledStoresObservationOnlyAndDoesNotCallScorer() {
		properties.getOpenai().setEnabled(false);
		NewsSignalScorer scorer = mock(NewsSignalScorer.class);
		OneKeywordNewsCollectionService service = service(scorer);

		CollectionRunCounts counts = service.collect("강남 재건축");

		assertThat(counts.status()).isEqualTo(CollectionRunStatus.SUCCEEDED);
		assertThat(counts.observedNewCount()).isEqualTo(1);
		assertThat(counts.observedDuplicateCount()).isZero();
		assertThat(counts.featureCreatedCount()).isZero();
		assertThat(counts.featureSkippedCount()).isEqualTo(1);
		assertThat(counts.failedCount()).isZero();
		assertThat(count("news.article_observation")).isEqualTo(1);
		assertThat(count("news.signal_feature")).isZero();
		assertThat(count("news.collection_run_article")).isEqualTo(1);
		assertThat(runFeatureSkippedCount()).isEqualTo(1);
		assertThat(runArticleStatus()).isEqualTo("FEATURE_SKIPPED");
		assertThat(runArticleFailureReason()).isEqualTo("SCORING_DISABLED");
		verifyNoInteractions(scorer);
	}

	@Test
	@DisplayName("OpenAI disabled rerun은 observation을 중복 저장하지 않고 first_seen_at을 보존한다")
	void openAiDisabledDuplicateRunKeepsObservationIdempotent() {
		properties.getOpenai().setEnabled(false);
		NewsSignalScorer scorer = mock(NewsSignalScorer.class);
		OneKeywordNewsCollectionService firstService = service(
			scorer,
			Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC)
		);
		OneKeywordNewsCollectionService secondService = service(
			scorer,
			Clock.fixed(Instant.parse("2026-01-03T00:00:00Z"), ZoneOffset.UTC)
		);

		firstService.collect("강남 재건축");
		CollectionRunCounts second = secondService.collect("강남 재건축");

		assertThat(second.status()).isEqualTo(CollectionRunStatus.SUCCEEDED);
		assertThat(second.observedDuplicateCount()).isEqualTo(1);
		assertThat(second.featureCreatedCount()).isZero();
		assertThat(second.featureSkippedCount()).isEqualTo(1);
		assertThat(second.failedCount()).isZero();
		assertThat(count("news.article_observation")).isEqualTo(1);
		assertThat(count("news.signal_feature")).isZero();
		assertThat(count("news.collection_run")).isEqualTo(2);
		assertThat(count("news.collection_run_article")).isEqualTo(2);
		assertThat(observationFirstSeenAt()).isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
		verifyNoInteractions(scorer);
	}

	@Test
	@DisplayName("feature 생성 실패 후 rerun은 duplicate observation의 누락 feature를 재시도한다")
	void rerunRetriesMissingFeatureForDuplicateObservation() {
		FailOnceSignalScorer scorer = new FailOnceSignalScorer(objectMapper);
		OneKeywordNewsCollectionService service = service(scorer);

		CollectionRunCounts first = service.collect("강남 재건축");
		CollectionRunCounts second = service.collect("강남 재건축");

		assertThat(first.status()).isEqualTo(CollectionRunStatus.PARTIAL);
		assertThat(first.failedCount()).isEqualTo(1);
		assertThat(second.status()).isEqualTo(CollectionRunStatus.SUCCEEDED);
		assertThat(second.observedDuplicateCount()).isEqualTo(1);
		assertThat(second.featureCreatedCount()).isEqualTo(1);
		assertThat(count("news.article_observation")).isEqualTo(1);
		assertThat(count("news.signal_feature")).isEqualTo(1);
	}

	@Test
	@DisplayName("scoring 실패가 observation 저장 후 발생해도 run article은 observation id를 보존한다")
	void scoringFailurePreservesRunArticleObservationLink() {
		OneKeywordNewsCollectionService service = service(new AlwaysFailSignalScorer());

		CollectionRunCounts counts = service.collect("강남 재건축");

		assertThat(counts.status()).isEqualTo(CollectionRunStatus.PARTIAL);
		assertThat(counts.observedNewCount()).isEqualTo(1);
		assertThat(counts.failedCount()).isEqualTo(1);
		assertThat(count("news.article_observation")).isEqualTo(1);
		assertThat(count("news.signal_feature")).isZero();
		assertThat(runArticleStatus()).isEqualTo("FAILED");
		assertThat(runArticleObservationId()).isEqualTo(observationId());
	}

	@Test
	@DisplayName("default disabled runner는 외부 service를 호출하지 않는다")
	void disabledRunnerHasNoSideEffects() throws Exception {
		NewsRuntimeProperties disabled = new NewsRuntimeProperties();
		OneKeywordNewsCollectionService service = mock(OneKeywordNewsCollectionService.class);
		RunOnceNewsApplicationRunner runner = new RunOnceNewsApplicationRunner(service, disabled);

		runner.run(null);

		verifyNoInteractions(service);
	}

	private OneKeywordNewsCollectionService service() {
		return service(new FakeSignalScorer(objectMapper));
	}

	private OneKeywordNewsCollectionService service(NewsSignalScorer signalScorer) {
		return service(signalScorer, Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC));
	}

	private OneKeywordNewsCollectionService service(NewsSignalScorer signalScorer, Clock clock) {
		return new OneKeywordNewsCollectionService(
			repository,
			new FakeMetadataClient(),
			signalScorer,
			properties,
			clock,
			objectMapper
		);
	}

	private int runFeatureSkippedCount() {
		return jdbcClient.sql("SELECT feature_skipped_count FROM news.collection_run")
			.query(Integer.class)
			.single();
	}

	private String runArticleStatus() {
		return jdbcClient.sql("SELECT discovery_status FROM news.collection_run_article")
			.query(String.class)
			.single();
	}

	private String runArticleFailureReason() {
		return jdbcClient.sql("SELECT failure_reason FROM news.collection_run_article")
			.query(String.class)
			.single();
	}

	private Long runArticleObservationId() {
		return jdbcClient.sql("SELECT article_observation_id FROM news.collection_run_article")
			.query(Long.class)
			.single();
	}

	private long observationId() {
		return jdbcClient.sql("SELECT id FROM news.article_observation")
			.query(Long.class)
			.single();
	}

	private Instant observationFirstSeenAt() {
		return jdbcClient.sql("SELECT first_seen_at FROM news.article_observation")
			.query(Timestamp.class)
			.single()
			.toInstant();
	}

	private NewsRuntimeProperties properties() {
		NewsRuntimeProperties properties = new NewsRuntimeProperties();
		properties.setEnabled(true);
		properties.getRunOnce().setEnabled(true);
		properties.getRunOnce().setQueryText("강남 재건축");
		properties.getRunOnce().setMaxKeywords(1);
		properties.getRunOnce().setMaxArticles(10);
		properties.getNaver().setDisplay(10);
		properties.getNaver().setSort("date");
		properties.getOpenai().setModel("test-model");
		return properties;
	}

	private String observationDatasetMetadata() {
		return jdbcClient.sql("""
			SELECT discovery_method || '|' || availability_basis || '|' || verification_status || '|' || model_dataset_tier
			FROM news.article_observation
			""").query(String.class).single();
	}

	private static class FakeMetadataClient implements NewsMetadataClient {

		@Override
		public NewsSearchResult search(String queryText, int display, String sortOrder) {
			String payload = "{\"title\":\"강남 재건축\",\"link\":\"https://n.news.naver.com/item\",\"description\":\"공급 확대\",\"pubDate\":\"Tue, 14 Nov 2023 15:30:00 +0900\"}";
			NewsArticleMetadata article = new NewsArticleMetadata(
				NewsSource.NAVER_NEWS_SEARCH,
				"stable-source-key",
				"example.com",
				"강남 재건축",
				"https://example.com/article",
				"https://n.news.naver.com/item",
				"공급 확대",
				Instant.parse("2023-11-14T06:30:00Z"),
				Instant.parse("2023-11-14T06:30:00Z"),
				LocalDate.of(2023, 11, 14),
				payload,
				TextDigests.sha256Hex(payload)
			);
			return new NewsSearchResult(1, 1, 1, List.of(article));
		}
	}

	private static class FailOnceSignalScorer extends FakeSignalScorer {

		private boolean failed;

		FailOnceSignalScorer(ObjectMapper objectMapper) {
			super(objectMapper);
		}

		@Override
		public NewsSignalExtraction score(ArticleObservationResult observation) {
			if (!failed) {
				failed = true;
				throw new NewsCollectionException("fake scoring failed");
			}
			return super.score(observation);
		}
	}

	private static class AlwaysFailSignalScorer implements NewsSignalScorer {

		@Override
		public NewsSignalExtraction score(ArticleObservationResult observation) {
			throw new NewsCollectionException("fake scoring failed");
		}
	}

	private static class FakeSignalScorer implements NewsSignalScorer {

		private final ObjectMapper objectMapper;

		FakeSignalScorer(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
		}

		@Override
		public NewsSignalExtraction score(ArticleObservationResult observation) {
			ObjectNode structuredOutput = objectMapper.createObjectNode();
			structuredOutput.putArray("region_tags").add("서울");
			structuredOutput.putArray("complex_candidates");
			structuredOutput.putArray("topic_tags").add("policy");
			structuredOutput.put("impact_target", "sale_price");
			structuredOutput.put("impact_direction", "up");
			structuredOutput.put("sentiment", "positive");
			structuredOutput.put("confidence", 0.875);
			structuredOutput.put("evidence_level", "snippet");
			return new NewsSignalExtraction(
				objectMapper.createArrayNode().add("서울"),
				objectMapper.createArrayNode(),
				objectMapper.createArrayNode().add("policy"),
				SignalImpactTarget.sale_price,
				SignalImpactDirection.up,
				SignalSentiment.positive,
				new BigDecimal("0.875"),
				SignalEvidenceLevel.snippet,
				structuredOutput
			);
		}
	}
}
