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
			"ai_research_seed_run",
			"collection_keyword",
			"collection_run",
			"collection_run_article",
			"collection_run_keyword",
			"model_experiment_signal_view",
			"production_observed_signal_view",
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
	@DisplayName("AI_ASSISTED_WEB_RESEARCH observation은 seed dataset metadata CHECK를 통과한다")
	void acceptsAiAssistedResearchSeedObservationMetadata() {
		long seedRunId = insertAiResearchSeedRun();

		long observationId = jdbcClient.sql("""
			INSERT INTO news.article_observation (
			    source,
			    source_key,
			    discovery_method,
			    availability_basis,
			    verification_status,
			    model_dataset_tier,
			    review_note_path,
			    ai_research_seed_run_id,
			    publisher,
			    title,
			    url,
			    provider_url,
			    first_seen_at,
			    collected_at,
			    news_date_kst,
			    raw_provider_payload,
			    payload_hash,
			    ingest_status
			)
			VALUES (
			    'AI_ASSISTED_WEB_RESEARCH',
			    'AI_ASSISTED_WEB_RESEARCH:source-key-1',
			    'OPENAI_WEB_SEARCH',
			    'AI_ASSISTED_RESEARCH_SEED',
			    'MANUAL_APPROVED',
			    'EXPERIMENTAL_SEED',
			    'news-research-seed/NATIONAL/2020/2020-06-02-source-key-1.md',
			    :seedRunId,
			    'example.com',
			    'title',
			    'https://example.com/article',
			    'https://example.com/article',
			    now(),
			    now(),
			    DATE '2020-06-02',
			    '{"published_date_precision":"DATE"}'::jsonb,
			    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
			    'OBSERVED'
			)
			RETURNING id
			""").param("seedRunId", seedRunId).query(Long.class).single();

		assertThat(observationId).isPositive();
	}

	@Test
	@DisplayName("기존 Naver observation은 realtime observed dataset default를 받는다")
	void naverObservationGetsRealtimeDatasetDefaults() {
		long observationId = insertObservation();

		String metadata = jdbcClient.sql("""
			SELECT discovery_method || '|' || availability_basis || '|' || verification_status || '|' || model_dataset_tier
			FROM news.article_observation
			WHERE id = :id
			""").param("id", observationId).query(String.class).single();

		assertThat(metadata).isEqualTo("PROVIDER_API|REALTIME_OBSERVED|SYSTEM_ACCEPTED|OBSERVED_SIGNAL");
	}

	@Test
	@DisplayName("model_experiment view는 seed와 observed signal을 포함하고 production view는 observed만 포함한다")
	void datasetViewsSeparateExperimentalSeedFromProductionObserved() {
		insertProfile();
		long seedRunId = insertAiResearchSeedRun();
		long observedId = insertObservation("NAVER_NEWS_SEARCH", "naver-source-key", null);
		long seedId = insertObservation("AI_ASSISTED_WEB_RESEARCH", "AI_ASSISTED_WEB_RESEARCH:seed-source-key", seedRunId);
		insertFeature(observedId, "NAVER_NEWS_SEARCH", "naver-source-key");
		insertFeature(seedId, "AI_ASSISTED_WEB_RESEARCH", "AI_ASSISTED_WEB_RESEARCH:seed-source-key");

		assertThat(datasetSourceKeys("news.model_experiment_signal_view"))
			.containsExactlyInAnyOrder("naver-source-key", "AI_ASSISTED_WEB_RESEARCH:seed-source-key");
		assertThat(datasetSourceKeys("news.production_observed_signal_view"))
			.containsExactly("naver-source-key");
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
		return insertObservation("NAVER_NEWS_SEARCH", "source-key-1", null);
	}

	private long insertObservation(String source, String sourceKey, Long seedRunId) {
		return jdbcClient.sql("""
			INSERT INTO news.article_observation (
			    source,
			    source_key,
			    discovery_method,
			    availability_basis,
			    verification_status,
			    model_dataset_tier,
			    review_note_path,
			    ai_research_seed_run_id,
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
			    :source,
			    :sourceKey,
			    CASE WHEN :source = 'AI_ASSISTED_WEB_RESEARCH' THEN 'OPENAI_WEB_SEARCH' ELSE 'PROVIDER_API' END,
			    CASE WHEN :source = 'AI_ASSISTED_WEB_RESEARCH' THEN 'AI_ASSISTED_RESEARCH_SEED' ELSE 'REALTIME_OBSERVED' END,
			    CASE WHEN :source = 'AI_ASSISTED_WEB_RESEARCH' THEN 'MANUAL_APPROVED' ELSE 'SYSTEM_ACCEPTED' END,
			    CASE WHEN :source = 'AI_ASSISTED_WEB_RESEARCH' THEN 'EXPERIMENTAL_SEED' ELSE 'OBSERVED_SIGNAL' END,
			    CASE WHEN :source = 'AI_ASSISTED_WEB_RESEARCH' THEN 'news-research-seed/NATIONAL/2020/2020-06-02-source-key-1.md' ELSE NULL END,
			    :seedRunId,
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
			""")
			.param("source", source)
			.param("sourceKey", sourceKey)
			.param("seedRunId", seedRunId)
			.query(Long.class).single();
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

	private long insertAiResearchSeedRun() {
		return jdbcClient.sql("""
			INSERT INTO news.ai_research_seed_run (
			    period_start,
			    period_end,
			    bucket_list,
			    target_candidates_per_bucket,
			    model,
			    prompt_version,
			    schema_version,
			    output_manifest_hash,
			    status,
			    started_at
			)
			VALUES (
			    DATE '2017-01-01',
			    DATE '2026-05-31',
			    '["NATIONAL"]'::jsonb,
			    15,
			    'test-model',
			    'prompt-v1',
			    'schema-v1',
			    'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
			    'RUNNING',
			    now()
			)
			RETURNING id
			""").query(Long.class).single();
	}

	private void insertFeature(long observationId, String source, String sourceKey) {
		jdbcClient.sql("""
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
			    :source,
			    :sourceKey,
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
			    'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
			    '{"region_tags":[],"complex_candidates":[],"topic_tags":["policy_regulation"],"impact_target":"sale_price","impact_direction":"up","sentiment":"positive","confidence":0.875,"evidence_level":"snippet"}'::jsonb
			)
			""")
			.param("observationId", observationId)
			.param("source", source)
			.param("sourceKey", sourceKey)
			.update();
	}

	private List<String> datasetSourceKeys(String viewName) {
		return jdbcClient.sql("SELECT source_key FROM " + viewName + " ORDER BY source_key")
			.query(String.class)
			.list();
	}
}
