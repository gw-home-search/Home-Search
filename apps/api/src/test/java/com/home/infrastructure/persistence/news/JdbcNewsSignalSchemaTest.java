package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class JdbcNewsSignalSchemaTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("news signal schema는 관측 시각 기준 cutoff 조회와 재생 가능한 feature 저장을 지원한다")
	void storesArticleObservationsAndSignalFeaturesWithFirstSeenCutoff() {
		seedSourcePolicy();
		Long articleObservationId = insertArticleObservation("naver-news:20260607:1", "OBSERVED", """
			{"providerId":"20260607-1","snippet":"Gangnam reconstruction policy update"}
			""");

		jdbcClient.sql("""
			INSERT INTO news_signal_feature (
			    article_observation_id,
			    source,
			    source_key,
			    feature_date_kst,
			    first_seen_at,
			    region_tags,
			    complex_candidates,
			    topic_tags,
			    impact_target,
			    impact_direction,
			    sentiment,
			    confidence,
			    extraction_version,
			    evidence_level
			)
			VALUES (
			    :articleObservationId,
			    'NAVER_NEWS',
			    'naver-news:20260607:1',
			    DATE '2026-06-07',
			    TIMESTAMPTZ '2026-06-07T09:35:00+09:00',
			    '["seoul", "gangnam-gu"]'::jsonb,
			    '[{"complexId":501,"confidence":0.42}]'::jsonb,
			    '["policy", "reconstruction"]'::jsonb,
			    'sale_price',
			    'up',
			    'positive',
			    0.8400,
			    'title-snippet-20260607',
			    'snippet'
			)
			""")
			.param("articleObservationId", articleObservationId)
			.update();

		assertThat(featureCountAtOrBefore(OffsetDateTime.parse("2026-06-07T09:34:59+09:00"))).isZero();
		assertThat(featureCountAtOrBefore(OffsetDateTime.parse("2026-06-07T09:35:00+09:00"))).isEqualTo(1L);
		assertThat(articleObservationColumns())
			.doesNotContain("content")
			.doesNotContain("body")
			.doesNotContain("full_text")
			.doesNotContain("html");
	}

	@Test
	@DisplayName("news article observation은 source_key와 extraction_version 기준 중복 저장을 막는다")
	void deduplicatesObservationsAndFeatureExtractionVersions() {
		seedSourcePolicy();
		Long articleObservationId = insertArticleObservation("naver-news:duplicate", "OBSERVED", """
			{"providerId":"duplicate","snippet":"duplicate check"}
			""");

		assertThatThrownBy(() -> insertArticleObservation("naver-news:duplicate", "DUPLICATE", """
			{"providerId":"duplicate-again","snippet":"duplicate check"}
			"""))
			.isInstanceOf(DataIntegrityViolationException.class);

		insertSignalFeature(articleObservationId, "title-snippet-20260607");
		assertThatThrownBy(() -> insertSignalFeature(articleObservationId, "title-snippet-20260607"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("news article observation은 provider payload에 원문 필드를 저장하지 않는다")
	void rejectsArticleBodyFieldsInProviderPayload() {
		seedSourcePolicy();

		assertThatThrownBy(() -> insertArticleObservation("naver-news:body-field", "OBSERVED", """
			{"providerId":"body-field","content":"article body must not be stored"}
			"""))
			.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> insertArticleObservation("naver-news:nested-body-field", "OBSERVED", """
			{"providerId":"nested-body-field","metadata":{"full_text":"nested article body must not be stored"}}
			"""))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void seedSourcePolicy() {
		jdbcClient.sql("""
			INSERT INTO news_source_policy (
			    source,
			    source_class,
			    usage_status,
			    full_text_allowed,
			    replacement_summary_allowed,
			    terms_url,
			    notes
			)
			VALUES (
			    'NAVER_NEWS',
			    'SEARCH_API',
			    'ALLOWED',
			    false,
			    false,
			    'https://developers.naver.com/docs/serviceapi/search/news/news.md',
			    'metadata and snippet only'
			)
			ON CONFLICT (source) DO NOTHING
			""").update();
	}

	private Long insertArticleObservation(String sourceKey, String status, String rawProviderPayload) {
		return jdbcClient.sql("""
			INSERT INTO news_article_observation (
			    source,
			    source_key,
			    publisher,
			    title,
			    url,
			    provider_url,
			    snippet,
			    published_at,
			    provider_pub_at,
			    first_seen_at,
			    collected_at,
			    news_date_kst,
			    raw_provider_payload,
			    payload_hash,
			    ingest_status
			)
			VALUES (
			    'NAVER_NEWS',
			    :sourceKey,
			    'Sample Publisher',
			    'Gangnam reconstruction policy update',
			    'https://example.com/news/1',
			    'https://developers.naver.com/docs/serviceapi/search/news/news.md',
			    'Gangnam reconstruction policy update',
			    TIMESTAMPTZ '2026-06-07T09:30:00+09:00',
			    TIMESTAMPTZ '2026-06-07T09:30:00+09:00',
			    TIMESTAMPTZ '2026-06-07T09:35:00+09:00',
			    TIMESTAMPTZ '2026-06-07T09:36:00+09:00',
			    DATE '2026-06-07',
			    :rawProviderPayload::jsonb,
			    'hash-' || :sourceKey,
			    :status
			)
			RETURNING id
			""")
			.param("sourceKey", sourceKey)
			.param("rawProviderPayload", rawProviderPayload)
			.param("status", status)
			.query(Long.class)
			.single();
	}

	private void insertSignalFeature(Long articleObservationId, String extractionVersion) {
		jdbcClient.sql("""
			INSERT INTO news_signal_feature (
			    article_observation_id,
			    source,
			    source_key,
			    feature_date_kst,
			    first_seen_at,
			    region_tags,
			    complex_candidates,
			    topic_tags,
			    impact_target,
			    impact_direction,
			    sentiment,
			    confidence,
			    extraction_version,
			    evidence_level
			)
			VALUES (
			    :articleObservationId,
			    'NAVER_NEWS',
			    'naver-news:duplicate',
			    DATE '2026-06-07',
			    TIMESTAMPTZ '2026-06-07T09:35:00+09:00',
			    '["seoul"]'::jsonb,
			    '[]'::jsonb,
			    '["policy"]'::jsonb,
			    'sale_price',
			    'mixed',
			    'neutral',
			    0.5000,
			    :extractionVersion,
			    'title'
			)
			""")
			.param("articleObservationId", articleObservationId)
			.param("extractionVersion", extractionVersion)
			.update();
	}

	private long featureCountAtOrBefore(OffsetDateTime predictionCutoff) {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM news_signal_feature
			WHERE first_seen_at <= :predictionCutoff
			""")
			.param("predictionCutoff", predictionCutoff)
			.query(Long.class)
			.single();
	}

	private String articleObservationColumns() {
		return jdbcClient.sql("""
			SELECT string_agg(column_name, ',' ORDER BY column_name)
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'news_article_observation'
			""")
			.query(String.class)
			.single();
	}
}
