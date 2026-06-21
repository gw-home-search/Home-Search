package com.home.news.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import com.home.domain.news.ArticleDiscoveryStatus;
import com.home.domain.news.CollectionRunMode;
import com.home.domain.news.CollectionRunStatus;
import com.home.domain.news.NewsKeywordType;
import com.home.domain.news.NewsObservationStatus;
import com.home.domain.news.NewsSource;
import com.home.news.application.ArticleObservationCommand;
import com.home.news.application.ArticleObservationResult;
import com.home.news.application.CollectionRunCounts;
import com.home.news.application.SignalFeatureCommand;
import com.home.news.application.SignalProfileCommand;
import com.home.news.support.TextDigests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class JdbcNewsRepositoryTest extends JdbcNewsPostgresTestSupport {

	private static final PostgreSQLContainer<?> POSTGRES = newPostgisContainer();
	private JdbcNewsRepository repository;

	static {
		POSTGRES.start();
	}

	@BeforeEach
	void setUp() {
		initializeJdbc(POSTGRES);
		newsFlyway().clean();
		newsFlyway().migrate();
		repository = new JdbcNewsRepository(jdbcClient);
	}

	@Test
	@DisplayName("같은 source/source_key article_observation은 하나만 저장하고 first_seen_at을 보존한다")
	void insertObservationIfAbsentIsIdempotent() {
		ArticleObservationCommand first = observation("source-key-1", Instant.parse("2026-01-01T00:00:00Z"));
		ArticleObservationCommand duplicate = observation("source-key-1", Instant.parse("2026-01-03T00:00:00Z"));

		ArticleObservationResult inserted = repository.insertObservationIfAbsent(first);
		ArticleObservationResult second = repository.insertObservationIfAbsent(duplicate);

		assertThat(inserted.created()).isTrue();
		assertThat(second.created()).isFalse();
		assertThat(second.id()).isEqualTo(inserted.id());
		assertThat(second.firstSeenAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
		assertThat(count("news.article_observation")).isEqualTo(1);
	}

	@Test
	@DisplayName("같은 extraction_version signal_feature는 observation당 하나만 저장한다")
	void insertFeatureIfAbsentIsIdempotent() {
		ArticleObservationResult observation = repository.insertObservationIfAbsent(observation("source-key-1", Instant.parse("2026-01-01T00:00:00Z")));
		insertProfile();
		SignalFeatureCommand command = feature(observation, "test-v1");

		assertThat(repository.insertFeatureIfAbsent(command).created()).isTrue();
		assertThat(repository.insertFeatureIfAbsent(command).created()).isFalse();

		assertThat(count("news.signal_feature")).isEqualTo(1);
	}

	@Test
	@DisplayName("dataset repository는 prediction_cutoff 이후 first_seen_at row를 제외한다")
	void datasetRowsApplyPredictionCutoff() {
		insertProfile();
		ArticleObservationResult before = repository.insertObservationIfAbsent(observation("source-key-1", Instant.parse("2026-01-01T00:00:00Z")));
		ArticleObservationResult after = repository.insertObservationIfAbsent(observation("source-key-2", Instant.parse("2026-01-05T00:00:00Z")));
		repository.insertFeatureIfAbsent(feature(before, "test-v1"));
		repository.insertFeatureIfAbsent(feature(after, "test-v1"));

		assertThat(repository.findDatasetRowsBefore(Instant.parse("2026-01-03T00:00:00Z")))
			.extracting(row -> row.sourceKey())
			.containsExactly("source-key-1");
	}

	@Test
	@DisplayName("run/run_keyword count는 durable하게 갱신된다")
	void updatesRunCounts() {
		long keywordId = repository.upsertManualKeyword("부동산 정책", NewsKeywordType.TOPIC);
		long runId = repository.createRun(CollectionRunMode.RUN_ONCE, "부동산 정책", 1, 1, 1);
		long runKeywordId = repository.createRunKeyword(runId, keywordId, "부동산 정책", NewsKeywordType.TOPIC, 1, "date");

		repository.updateRunKeywordCounts(runKeywordId, CollectionRunStatus.SUCCEEDED, 10, 1, 1, 1, 1, 0, 1, 0, null);
		repository.finalizeRun(runId, new CollectionRunCounts(CollectionRunStatus.SUCCEEDED, 1, 1, 1, 0, 1, 0, 0, null));

		assertThat(repository.runStatus(runId)).isEqualTo("SUCCEEDED");
		assertThat(count("news.collection_run")).isEqualTo(1);
		assertThat(count("news.collection_run_keyword")).isEqualTo(1);
	}

	@Test
	@DisplayName("run article 실패 갱신은 기존 article_observation_id를 null로 덮지 않는다")
	void recordRunArticleFailureUpdateDoesNotClearObservationId() {
		long keywordId = repository.upsertManualKeyword("부동산 정책", NewsKeywordType.TOPIC);
		long runId = repository.createRun(CollectionRunMode.RUN_ONCE, "부동산 정책", 1, 1, 1);
		long runKeywordId = repository.createRunKeyword(runId, keywordId, "부동산 정책", NewsKeywordType.TOPIC, 1, "date");
		ArticleObservationResult observation = repository.insertObservationIfAbsent(
			observation("source-key-1", Instant.parse("2026-01-01T00:00:00Z"))
		);

		repository.recordRunArticle(
			runKeywordId,
			observation.id(),
			observation.source(),
			observation.sourceKey(),
			1,
			observation.title(),
			observation.providerUrl(),
			ArticleDiscoveryStatus.NEW_OBSERVATION,
			null
		);
		repository.recordRunArticle(
			runKeywordId,
			null,
			observation.source(),
			observation.sourceKey(),
			1,
			observation.title(),
			observation.providerUrl(),
			ArticleDiscoveryStatus.FAILED,
			"fake scoring failed"
		);

		assertThat(runArticleObservationId()).isEqualTo(observation.id());
	}

	private ArticleObservationCommand observation(String sourceKey, Instant firstSeenAt) {
		String rawPayload = "{\"title\":\"title\",\"link\":\"https://news.naver.com/item\",\"description\":\"snippet\",\"pubDate\":\"Tue, 14 Nov 2023 15:30:00 +0900\"}";
		return new ArticleObservationCommand(
			NewsSource.NAVER_NEWS_SEARCH,
			sourceKey,
			"example.com",
			"title",
			"https://example.com/article",
			"https://news.naver.com/item",
			"snippet",
			Instant.parse("2023-11-14T06:30:00Z"),
			Instant.parse("2023-11-14T06:30:00Z"),
			firstSeenAt,
			firstSeenAt,
			LocalDate.of(2023, 11, 14),
			rawPayload,
			TextDigests.sha256Hex(rawPayload),
			NewsObservationStatus.OBSERVED
		);
	}

	private void insertProfile() {
		repository.insertSignalProfileIfAbsent(new SignalProfileCommand(
			"test-v1",
			"OPENAI",
			"test-model",
			"prompt-v1",
			"schema-v1",
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
			true
		));
	}

	private Long runArticleObservationId() {
		return jdbcClient.sql("SELECT article_observation_id FROM news.collection_run_article")
			.query(Long.class)
			.single();
	}

	private SignalFeatureCommand feature(ArticleObservationResult observation, String extractionVersion) {
		return new SignalFeatureCommand(
			observation.id(),
			observation.source(),
			observation.sourceKey(),
			observation.newsDateKst(),
			observation.firstSeenAt(),
			"[\"서울\"]",
			"[]",
			"[\"policy\"]",
			"sale_price",
			"up",
			"positive",
			"0.875",
			extractionVersion,
			"snippet",
			"test-model",
			"prompt-v1",
			"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
			"{\"region_tags\":[\"서울\"],\"complex_candidates\":[],\"topic_tags\":[\"policy\"],\"impact_target\":\"sale_price\",\"impact_direction\":\"up\",\"sentiment\":\"positive\",\"confidence\":0.875,\"evidence_level\":\"snippet\"}"
		);
	}
}
