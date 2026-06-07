package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import com.home.application.news.NewsArticleObservationCleanupRecord;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcNewsArticleObservationCleanupRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("Jdbc news observation cleanup repository는 provider payload만 제거하고 dedupe identity는 보존한다")
	void purgesProviderPayloadsWithoutDeletingObservationRows() {
		seedSourcePolicy();
		insertArticleObservation(
			"naver-news:featured",
			"FEATURED",
			"2026-06-07T09:01:00+09:00",
			"""
				{"providerId":"featured","snippet":"already featured"}
				"""
		);
		insertArticleObservation(
			"naver-news:skipped",
			"SKIPPED_IRRELEVANT",
			"2026-06-07T09:01:00+09:00",
			"""
				{"providerId":"skipped","snippet":"irrelevant"}
				"""
		);
		insertArticleObservation(
			"naver-news:stale-failed",
			"FETCH_FAILED",
			"2026-06-02T09:01:00+09:00",
			"""
				{"providerId":"stale-failed","snippet":"old failed fetch"}
				"""
		);
		insertArticleObservation(
			"naver-news:fresh-failed",
			"FETCH_FAILED",
			"2026-06-07T09:01:00+09:00",
			"""
				{"providerId":"fresh-failed","snippet":"fresh failed fetch"}
				"""
		);
		JdbcNewsArticleObservationCleanupRepository repository = new JdbcNewsArticleObservationCleanupRepository(
			jdbcClient
		);

		assertThat(repository.purgeProviderPayloads(OffsetDateTime.parse("2026-06-05T00:00:00+09:00")))
			.extracting(NewsArticleObservationCleanupRecord::sourceKey)
			.containsExactlyInAnyOrder("naver-news:featured", "naver-news:skipped", "naver-news:stale-failed");

		assertThat(payloadBySourceKey("naver-news:featured")).isEqualTo("{}");
		assertThat(payloadBySourceKey("naver-news:skipped")).isEqualTo("{}");
		assertThat(payloadBySourceKey("naver-news:stale-failed")).isEqualTo("{}");
		assertThat(payloadBySourceKey("naver-news:fresh-failed")).contains("fresh-failed");
		assertThat(observationCount()).isEqualTo(4L);
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

	private void insertArticleObservation(String sourceKey, String status, String collectedAt, String rawProviderPayload) {
		jdbcClient.sql("""
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
			    TIMESTAMPTZ '2026-06-07T09:00:00+09:00',
			    TIMESTAMPTZ '2026-06-07T09:00:00+09:00',
			    TIMESTAMPTZ '2026-06-07T09:00:00+09:00',
			    :collectedAt::timestamptz,
			    DATE '2026-06-07',
			    :rawProviderPayload::jsonb,
			    'hash-' || :sourceKey,
			    :status
			)
			""")
			.param("sourceKey", sourceKey)
			.param("status", status)
			.param("collectedAt", collectedAt)
			.param("rawProviderPayload", rawProviderPayload)
			.update();
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
