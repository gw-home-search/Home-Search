package com.home.news.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class NewsSignalSchemaMigrationTest extends JdbcNewsPostgresTestSupport {

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
	@DisplayName("news Flyway는 news schema history와 signal tables/views를 생성한다")
	void createsNewsSignalSchema() {
		assertThat(newsRelations()).containsExactlyInAnyOrder(
			"article_observation",
			"article_observation_cleanup_candidate_view",
			"collection_keyword",
			"collection_run",
			"collection_run_article",
			"collection_run_keyword",
			"signal_dataset_view",
			"signal_extraction_profile",
			"signal_feature",
			"source_policy"
		);
		assertThat(toRegclass("news.flyway_schema_history")).isTrue();
		assertThat(toRegclass("public.flyway_schema_history")).isFalse();
	}

	@Test
	@DisplayName("news schema는 article body/summary 컬럼을 노출하지 않는다")
	void doesNotExposeForbiddenArticleTextColumns() {
		Long count = jdbcClient.sql("""
			SELECT count(*)
			FROM information_schema.columns
			WHERE table_schema = 'news'
			  AND column_name IN ('content', 'body', 'full_text', 'html', 'summary')
			""").query(Long.class).single();

		assertThat(count).isZero();
	}

	@Test
	@DisplayName("article_observation raw_provider_payload는 forbidden JSON key를 거부한다")
	void rejectsForbiddenRawProviderPayloadKeys() {
		assertThatThrownBy(() -> jdbcClient.sql("""
			INSERT INTO news.article_observation (
			    source,
			    source_key,
			    publisher,
			    title,
			    provider_url,
			    first_seen_at,
			    collected_at,
			    news_date_kst,
			    raw_provider_payload,
			    payload_hash,
			    ingest_status
			)
			VALUES (
			    'NAVER_NEWS_SEARCH',
			    'source-key-1',
			    'example.com',
			    'title',
			    'https://news.naver.com/item',
			    now(),
			    now(),
			    CURRENT_DATE,
			    '{"content":"forbidden"}'::jsonb,
			    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
			    'OBSERVED'
			)
			""").update()).hasMessageContaining("article_observation_raw_provider_payload_no_body");
	}

	@Test
	@DisplayName("signal_feature structured_output은 forbidden JSON key를 거부한다")
	void rejectsForbiddenStructuredOutputKeys() {
		long observationId = insertObservation();
		insertProfile();

		assertThatThrownBy(() -> jdbcClient.sql("""
			INSERT INTO news.signal_feature (
			    article_observation_id,
			    source,
			    source_key,
			    feature_date_kst,
			    first_seen_at,
			    impact_target,
			    impact_direction,
			    sentiment,
			    confidence,
			    extraction_version,
			    evidence_level,
			    model,
			    prompt_version,
			    input_hash,
			    structured_output
			)
			VALUES (
			    :observationId,
			    'NAVER_NEWS_SEARCH',
			    'source-key-1',
			    CURRENT_DATE,
			    now(),
			    'sale_price',
			    'up',
			    'positive',
			    0.875,
			    'test-v1',
			    'snippet',
			    'test-model',
			    'prompt-v1',
			    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
			    '{"summary":"forbidden"}'::jsonb
			)
			""").param("observationId", observationId).update()).hasMessageContaining("signal_feature_structured_output_no_body");
	}

	private List<String> newsRelations() {
		return jdbcClient.sql("""
			SELECT relname
			FROM pg_class c
			JOIN pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = 'news'
			  AND c.relkind IN ('r', 'v')
			  AND c.relname <> 'flyway_schema_history'
			ORDER BY relname
			""").query(String.class).list();
	}

	private boolean toRegclass(String relationName) {
		return Boolean.TRUE.equals(jdbcClient.sql("SELECT to_regclass(:relationName) IS NOT NULL")
			.param("relationName", relationName)
			.query(Boolean.class)
			.single());
	}

	private long insertObservation() {
		return jdbcClient.sql("""
			INSERT INTO news.article_observation (
			    source,
			    source_key,
			    publisher,
			    title,
			    provider_url,
			    first_seen_at,
			    collected_at,
			    news_date_kst,
			    payload_hash,
			    ingest_status
			)
			VALUES (
			    'NAVER_NEWS_SEARCH',
			    'source-key-1',
			    'example.com',
			    'title',
			    'https://news.naver.com/item',
			    now(),
			    now(),
			    CURRENT_DATE,
			    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
			    'OBSERVED'
			)
			RETURNING id
			""").query(Long.class).single();
	}

	private void insertProfile() {
		jdbcClient.sql("""
			INSERT INTO news.signal_extraction_profile (
			    extraction_version,
			    model,
			    prompt_version,
			    schema_version,
			    prompt_hash,
			    json_schema_hash
			)
			VALUES (
			    'test-v1',
			    'test-model',
			    'prompt-v1',
			    'schema-v1',
			    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
			    'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
			)
			""").update();
	}
}
