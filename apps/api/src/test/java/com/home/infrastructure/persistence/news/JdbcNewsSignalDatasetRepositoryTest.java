package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.signal.NewsSignalDatasetRepository;
import com.home.application.news.signal.NewsSignalDatasetRow;
import com.home.application.news.signal.NewsSignalFeatureExtractionPolicy;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcNewsSignalDatasetRepositoryTest extends JdbcPostgresTestSupport {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("Jdbc news signal dataset repository는 first_seen_at cutoff 기준으로 prediction-safe feature만 조회한다")
	void findsPredictionSafeFeaturesAtOrBeforeCutoff() {
		seedSourcePolicy();
		Long visibleArticleId = insertArticleObservation(
			"naver-news:visible",
			"2026-06-01T09:00:00+09:00",
			"2026-06-07T09:00:00+09:00"
		);
		Long futureDiscoveredArticleId = insertArticleObservation(
			"naver-news:future-discovered",
			"2026-06-01T09:00:00+09:00",
			"2026-06-08T09:00:00+09:00"
		);
		insertSignalFeature(
			visibleArticleId,
			"naver-news:visible",
			"2026-06-07T09:00:00+09:00",
			"title-snippet-signal-20260607-r1"
		);
		insertSignalFeature(
			visibleArticleId,
			"naver-news:visible",
			"2026-06-07T09:00:00+09:00",
			NewsSignalFeatureExtractionPolicy.DEFAULT_EXTRACTION_VERSION
		);
		insertSignalFeature(
			futureDiscoveredArticleId,
			"naver-news:future-discovered",
			"2026-06-08T09:00:00+09:00",
			NewsSignalFeatureExtractionPolicy.DEFAULT_EXTRACTION_VERSION
		);
		NewsSignalDatasetRepository repository = new JdbcNewsSignalDatasetRepository(jdbcClient, objectMapper);

		assertThat(repository.findAtOrBefore(OffsetDateTime.parse("2026-06-07T23:59:59+09:00"), 100))
			.singleElement()
			.satisfies(row -> {
				assertThat(row.sourceKey()).isEqualTo("naver-news:visible");
				assertThat(row.titleKeywords()).containsExactly("강남", "재건축", "집값");
				assertThat(row.extractionVersion())
					.isEqualTo(NewsSignalFeatureExtractionPolicy.DEFAULT_EXTRACTION_VERSION);
			});
		assertThat(repository.findAtOrBefore(OffsetDateTime.parse("2026-06-09T00:00:00+09:00"), 100))
			.extracting(NewsSignalDatasetRow::sourceKey)
			.containsExactly("naver-news:visible", "naver-news:future-discovered");
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

	private Long insertArticleObservation(String sourceKey, String publishedAt, String firstSeenAt) {
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
			    :firstSeenAt::timestamptz,
			    (:firstSeenAt::timestamptz AT TIME ZONE 'Asia/Seoul')::date,
			    jsonb_build_object('title', 'Gangnam reconstruction policy update'),
			    'hash-' || :sourceKey,
			    'FEATURED'
			)
			RETURNING id
			""")
			.param("sourceKey", sourceKey)
			.param("publishedAt", publishedAt)
			.param("firstSeenAt", firstSeenAt)
			.query(Long.class)
			.single();
	}

	private void insertSignalFeature(
		Long articleObservationId,
		String sourceKey,
		String firstSeenAt,
		String extractionVersion
	) {
		jdbcClient.sql("""
			INSERT INTO news_signal_feature (
			    article_observation_id,
			    source,
			    source_key,
			    feature_date_kst,
			    first_seen_at,
			    title_keywords,
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
			    '["강남", "재건축", "집값"]'::jsonb,
			    '["seoul", "gangnam-gu"]'::jsonb,
			    '[]'::jsonb,
			    '["policy", "reconstruction"]'::jsonb,
			    'sale_price',
			    'up',
			    'positive',
			    0.8400,
			    :extractionVersion,
			    'snippet'
			)
			""")
			.param("articleObservationId", articleObservationId)
			.param("sourceKey", sourceKey)
			.param("firstSeenAt", firstSeenAt)
			.param("extractionVersion", extractionVersion)
			.update();
	}
}
