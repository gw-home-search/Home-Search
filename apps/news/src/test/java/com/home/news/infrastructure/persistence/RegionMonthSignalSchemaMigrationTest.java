package com.home.news.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class RegionMonthSignalSchemaMigrationTest extends JdbcNewsPostgresTestSupport {

	private static final PostgreSQLContainer<?> POSTGRES = newPostgisContainer();

	static {
		POSTGRES.start();
	}

	@BeforeEach
	void migrate() {
		initializeJdbc(POSTGRES);
		newsFlyway().clean();
		newsFlyway().migrate();
	}

	@Test
	@DisplayName("news Flyway는 aggregate 전용 schema만 생성한다")
	void createsAggregateOnlySchema() {
		assertThat(newsRelations()).containsExactly(
			"flyway_schema_history",
			"region_month_signal_evidence",
			"region_month_signal_import_run",
			"region_month_signal_snapshot"
		);
	}

	@Test
	@DisplayName("obsolete article/Naver/OpenAI relation은 생성하지 않는다")
	void doesNotCreateObsoleteArticleRuntimeTables() {
		assertThat(newsRelations()).doesNotContain(
			"article_observation",
			"signal_feature",
			"collection_run",
			"collection_keyword",
			"ai_research_seed_run",
			"source_policy"
		);
	}

	@Test
	@DisplayName("snapshot score와 confidence constraint는 invalid 값을 거부한다")
	void rejectsInvalidScoreAndConfidence() {
		assertThatThrownBy(() -> insertSnapshot("bad-score", 101, "0.500", "valid aggregate note"))
			.hasMessageContaining("region_month_signal_snapshot_scores_range");
		assertThatThrownBy(() -> insertSnapshot("bad-confidence", 50, "1.100", "valid aggregate note"))
			.hasMessageContaining("region_month_signal_snapshot_confidence_range");
	}

	@Test
	@DisplayName("snapshot aggregate_note는 forbidden body-like text를 거부한다")
	void rejectsForbiddenAggregateNoteText() {
		assertThatThrownBy(() -> insertSnapshot("bad-note", 50, "0.500", "기사본문 저장 금지"))
			.hasMessageContaining("region_month_signal_snapshot_no_forbidden_note");
	}

	private void insertSnapshot(String key, int score, String confidence, String note) {
		jdbcClient.sql("""
			INSERT INTO news.region_month_signal_snapshot (
			    region_bucket,
			    signal_month,
			    source_kind,
			    method_version,
			    dataset_tier,
			    policy_positive_score,
			    confidence,
			    aggregate_note
			)
			VALUES (
			    'NATIONAL',
			    DATE '2020-06-01',
			    'BIGKINDS_CSV',
			    :key,
			    'EXPERIMENTAL_SEED',
			    :score,
			    CAST(:confidence AS numeric),
			    :note
			)
			""")
			.param("key", key)
			.param("score", score)
			.param("confidence", confidence)
			.param("note", note)
			.update();
	}
}
