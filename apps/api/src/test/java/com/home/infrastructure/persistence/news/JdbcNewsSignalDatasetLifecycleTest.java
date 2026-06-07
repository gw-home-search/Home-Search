package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcNewsSignalDatasetLifecycleTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("news signal dataset view는 first_seen_at cutoff 기준으로 모델 입력 누수를 막는다")
	void datasetViewUsesFirstSeenCutoffForPredictionSafeRows() {
		seedSourcePolicy();
		Long visibleArticleId = insertArticleObservation(
			"naver-news:visible",
			"FEATURED",
			"2026-06-01T09:00:00+09:00",
			"2026-06-07T09:00:00+09:00",
			"2026-06-07T09:01:00+09:00",
			"""
				{"providerId":"visible","snippet":"visible before cutoff"}
				"""
		);
		Long leakedArticleId = insertArticleObservation(
			"naver-news:future-discovered",
			"FEATURED",
			"2026-06-01T09:00:00+09:00",
			"2026-06-08T09:00:00+09:00",
			"2026-06-08T09:01:00+09:00",
			"""
				{"providerId":"future-discovered","snippet":"published earlier but seen later"}
				"""
		);
		insertSignalFeature(visibleArticleId, "naver-news:visible", "2026-06-07T09:00:00+09:00");
		insertSignalFeature(leakedArticleId, "naver-news:future-discovered", "2026-06-08T09:00:00+09:00");

		assertThat(datasetSourceKeysAtOrBefore("2026-06-07T23:59:59+09:00"))
			.containsExactly("naver-news:visible");
		assertThat(datasetColumns())
			.contains(
				"feature_id",
				"article_observation_id",
				"source_key",
				"title",
				"title_keywords",
				"url",
				"snippet"
			)
			.doesNotContain("raw_provider_payload")
			.doesNotContain("content")
			.doesNotContain("body")
			.doesNotContain("full_text")
			.doesNotContain("html");
	}

	@Test
	@DisplayName("news observation cleanup은 dedupe 식별자는 남기고 불필요한 provider payload만 제거한다")
	void purgesProviderPayloadsWithoutDeletingDedupeIdentities() {
		seedSourcePolicy();
		insertArticleObservation(
			"naver-news:featured",
			"FEATURED",
			"2026-06-01T09:00:00+09:00",
			"2026-06-07T09:00:00+09:00",
			"2026-06-07T09:01:00+09:00",
			"""
				{"providerId":"featured","snippet":"already featured"}
				"""
		);
		insertArticleObservation(
			"naver-news:skipped",
			"SKIPPED_IRRELEVANT",
			"2026-06-01T09:00:00+09:00",
			"2026-06-07T09:00:00+09:00",
			"2026-06-07T09:01:00+09:00",
			"""
				{"providerId":"skipped","snippet":"irrelevant"}
				"""
		);
		insertArticleObservation(
			"naver-news:terms-blocked",
			"TERMS_BLOCKED",
			"2026-06-01T09:00:00+09:00",
			"2026-06-07T09:00:00+09:00",
			"2026-06-07T09:01:00+09:00",
			"""
				{"providerId":"terms-blocked","snippet":"terms blocked"}
				"""
		);
		insertArticleObservation(
			"naver-news:stale-failed",
			"FETCH_FAILED",
			"2026-06-01T09:00:00+09:00",
			"2026-06-07T09:00:00+09:00",
			"2026-06-02T09:01:00+09:00",
			"""
				{"providerId":"stale-failed","snippet":"old failed fetch"}
				"""
		);
		insertArticleObservation(
			"naver-news:fresh-failed",
			"FETCH_FAILED",
			"2026-06-01T09:00:00+09:00",
			"2026-06-07T09:00:00+09:00",
			"2026-06-07T09:01:00+09:00",
			"""
				{"providerId":"fresh-failed","snippet":"fresh failed fetch"}
				"""
		);

		assertThat(cleanupCandidateActions())
			.containsEntry("naver-news:featured", "PURGE_PAYLOAD")
			.containsEntry("naver-news:skipped", "PURGE_PAYLOAD")
			.containsEntry("naver-news:terms-blocked", "PURGE_PAYLOAD")
			.containsEntry("naver-news:stale-failed", "PURGE_PAYLOAD_AFTER_RETRY_WINDOW")
			.containsEntry("naver-news:fresh-failed", "PURGE_PAYLOAD_AFTER_RETRY_WINDOW");

		assertThat(purgePayloads("2026-06-05T00:00:00+09:00"))
			.containsExactlyInAnyOrder(
				"naver-news:featured",
				"naver-news:skipped",
				"naver-news:stale-failed",
				"naver-news:terms-blocked"
			);

		assertThat(payloadBySourceKey("naver-news:featured")).isEqualTo("{}");
		assertThat(payloadBySourceKey("naver-news:skipped")).isEqualTo("{}");
		assertThat(payloadBySourceKey("naver-news:terms-blocked")).isEqualTo("{}");
		assertThat(payloadBySourceKey("naver-news:stale-failed")).isEqualTo("{}");
		assertThat(payloadBySourceKey("naver-news:fresh-failed")).contains("fresh-failed");
		assertThat(observationCount()).isEqualTo(5L);
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

	private Long insertArticleObservation(
		String sourceKey,
		String status,
		String publishedAt,
		String firstSeenAt,
		String collectedAt,
		String rawProviderPayload
	) {
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
			    'https://example.com/news/' || :sourceKey,
			    'https://developers.naver.com/docs/serviceapi/search/news/news.md',
			    'Gangnam reconstruction policy update',
			    :publishedAt::timestamptz,
			    :publishedAt::timestamptz,
			    :firstSeenAt::timestamptz,
			    :collectedAt::timestamptz,
			    (:firstSeenAt::timestamptz AT TIME ZONE 'Asia/Seoul')::date,
			    :rawProviderPayload::jsonb,
			    'hash-' || :sourceKey,
			    :status
			)
			RETURNING id
			""")
			.param("sourceKey", sourceKey)
			.param("status", status)
			.param("publishedAt", publishedAt)
			.param("firstSeenAt", firstSeenAt)
			.param("collectedAt", collectedAt)
			.param("rawProviderPayload", rawProviderPayload)
			.query(Long.class)
			.single();
	}

	private void insertSignalFeature(Long articleObservationId, String sourceKey, String firstSeenAt) {
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
			    :sourceKey,
			    (:firstSeenAt::timestamptz AT TIME ZONE 'Asia/Seoul')::date,
			    :firstSeenAt::timestamptz,
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
			.param("sourceKey", sourceKey)
			.param("firstSeenAt", firstSeenAt)
			.update();
	}

	private List<String> datasetSourceKeysAtOrBefore(String predictionCutoff) {
		return jdbcClient.sql("""
			SELECT source_key
			FROM news_signal_dataset_view
			WHERE first_seen_at <= :predictionCutoff::timestamptz
			ORDER BY source_key
			""")
			.param("predictionCutoff", predictionCutoff)
			.query(String.class)
			.list();
	}

	private String datasetColumns() {
		return jdbcClient.sql("""
			SELECT string_agg(column_name, ',' ORDER BY column_name)
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'news_signal_dataset_view'
			""")
			.query(String.class)
			.single();
	}

	private Map<String, String> cleanupCandidateActions() {
		return jdbcClient.sql("""
			SELECT source_key, retention_action
			FROM news_article_observation_cleanup_candidate_view
			ORDER BY source_key
			""")
			.query((resultSet, rowNumber) -> Map.entry(
				resultSet.getString("source_key"),
				resultSet.getString("retention_action")
			))
			.list()
			.stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private List<String> purgePayloads(String retentionCutoff) {
		return jdbcClient.sql("""
			SELECT source_key
			FROM purge_news_article_observation_payloads(:retentionCutoff::timestamptz)
			ORDER BY source_key
			""")
			.param("retentionCutoff", retentionCutoff)
			.query(String.class)
			.list();
	}

	private String payloadBySourceKey(String sourceKey) {
		return jdbcClient.sql("""
			SELECT raw_provider_payload::text
			FROM news_article_observation
			WHERE source_key = :sourceKey
			""")
			.param("sourceKey", sourceKey)
			.query(String.class)
			.single();
	}

	private long observationCount() {
		return jdbcClient.sql("SELECT count(*) FROM news_article_observation")
			.query(Long.class)
			.single();
	}
}
