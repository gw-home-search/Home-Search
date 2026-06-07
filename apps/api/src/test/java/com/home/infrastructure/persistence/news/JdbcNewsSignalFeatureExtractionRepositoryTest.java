package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleRelevanceDecisionType;
import com.home.application.news.NewsSignalFeatureCommand;
import com.home.application.news.NewsSignalFeatureExtractionCandidate;
import com.home.application.news.NewsSignalFeatureExtractionPolicy;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcNewsSignalFeatureExtractionRepositoryTest extends JdbcPostgresTestSupport {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("Jdbc news signal feature extraction repository는 최신 relevance keep/review 후보만 조회하고 feature를 중복 없이 저장한다")
	void findsLatestKeepOrReviewCandidatesAndStoresFeatureOnce() {
		seedSourcePolicy();
		Long keepId = insertArticleObservation("naver-news:keep", "OBSERVED", "강남 재건축 규제 완화", "서울 아파트 상승");
		Long reviewId = insertArticleObservation("naver-news:review", "OBSERVED", "한국은행 기준금리 동결", "부동산 시장 관망");
		Long skippedId = insertArticleObservation("naver-news:skipped", "OBSERVED", "강남 아파트 언급 정치 뉴스", "총리 후보자");
		Long featuredId = insertArticleObservation("naver-news:featured", "FEATURED", "강남 재건축", "이미 feature");
		Long oldKeepLatestSkipId = insertArticleObservation("naver-news:latest-skip", "OBSERVED", "아파트 언급 정치 뉴스", "후보자");
		insertDecision(keepId, "naver-news:keep", "KEEP", "2026-06-07T09:40:00+09:00");
		insertDecision(reviewId, "naver-news:review", "REVIEW", "2026-06-07T09:41:00+09:00");
		insertDecision(skippedId, "naver-news:skipped", "SKIP_IRRELEVANT", "2026-06-07T09:42:00+09:00");
		insertDecision(featuredId, "naver-news:featured", "KEEP", "2026-06-07T09:43:00+09:00");
		insertDecision(oldKeepLatestSkipId, "naver-news:latest-skip", "KEEP", "2026-06-07T09:44:00+09:00");
		insertDecision(oldKeepLatestSkipId, "naver-news:latest-skip", "SKIP_IRRELEVANT", "2026-06-07T09:45:00+09:00");
		JdbcNewsSignalFeatureExtractionRepository repository = new JdbcNewsSignalFeatureExtractionRepository(
			jdbcClient,
			objectMapper
		);

		List<NewsSignalFeatureExtractionCandidate> candidates = repository.findPendingCandidates(
			10,
			NewsSignalFeatureExtractionPolicy.DEFAULT_EXTRACTION_VERSION
		);

		assertThat(candidates).extracting(NewsSignalFeatureExtractionCandidate::sourceKey)
			.containsExactly("naver-news:keep", "naver-news:review", "naver-news:featured");
		assertThat(candidates).extracting(NewsSignalFeatureExtractionCandidate::relevanceDecisionType)
			.containsExactly(
				NewsArticleRelevanceDecisionType.KEEP,
				NewsArticleRelevanceDecisionType.REVIEW,
				NewsArticleRelevanceDecisionType.KEEP
			);

		assertThat(repository.saveFeatureIfAbsent(command(keepId, "naver-news:keep"))).isTrue();
		assertThat(repository.saveFeatureIfAbsent(command(keepId, "naver-news:keep"))).isFalse();
		assertThat(repository.markFeaturedIfObserved(keepId)).isTrue();
		assertThat(repository.markFeaturedIfObserved(keepId)).isFalse();

		assertThat(featureCount()).isEqualTo(1L);
		assertThat(titleKeywordsOf("naver-news:keep")).isEqualTo("[\"강남\", \"재건축\"]");
		assertThat(statusOf(keepId)).isEqualTo("FEATURED");
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

	private Long insertArticleObservation(String sourceKey, String status, String title, String snippet) {
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
			    'example.com',
			    :title,
			    'https://example.com/news/' || :sourceKey,
			    'https://n.news.naver.com/mnews/article/001/0000000001',
			    :snippet,
			    TIMESTAMPTZ '2026-06-07T09:30:00+09:00',
			    TIMESTAMPTZ '2026-06-07T09:30:00+09:00',
			    TIMESTAMPTZ '2026-06-07T09:35:00+09:00',
			    TIMESTAMPTZ '2026-06-07T09:36:00+09:00',
			    DATE '2026-06-07',
			    jsonb_build_object('title', :title, 'description', :snippet),
			    'hash-' || :sourceKey,
			    :status
			)
			RETURNING id
			""")
			.param("sourceKey", sourceKey)
			.param("title", title)
			.param("snippet", snippet)
			.param("status", status)
			.query(Long.class)
			.single();
	}

	private void insertDecision(Long articleObservationId, String sourceKey, String decision, String evaluatedAt) {
		jdbcClient.sql("""
			INSERT INTO news_relevance_decision (
			    article_observation_id,
			    source,
			    source_key,
			    policy_version,
			    decision,
			    score,
			    threshold,
			    reason_codes,
			    matched_terms,
			    evaluated_at
			)
			VALUES (
			    :articleObservationId,
			    'NAVER_NEWS',
			    :sourceKey,
			    'rule-title-snippet-20260607-r2-' || :decision,
			    :decision,
			    0.7000,
			    0.3500,
			    '["TEST"]'::jsonb,
			    '{}'::jsonb,
			    :evaluatedAt::timestamptz
			)
			""")
			.param("articleObservationId", articleObservationId)
			.param("sourceKey", sourceKey)
			.param("decision", decision)
			.param("evaluatedAt", evaluatedAt)
			.update();
	}

	private static NewsSignalFeatureCommand command(Long articleObservationId, String sourceKey) {
		return new NewsSignalFeatureCommand(
			articleObservationId,
			"NAVER_NEWS",
			sourceKey,
			java.time.LocalDate.parse("2026-06-07"),
			OffsetDateTime.parse("2026-06-07T09:35:00+09:00"),
			List.of("강남", "재건축"),
			List.of("seoul", "gangnam-gu"),
			List.of(Map.of("complexId", 501, "confidence", 0.42)),
			List.of("policy", "reconstruction"),
			"sale_price",
			"up",
			"positive",
			0.8400,
			NewsSignalFeatureExtractionPolicy.DEFAULT_EXTRACTION_VERSION,
			"snippet"
		);
	}

	private long featureCount() {
		return jdbcClient.sql("SELECT count(*) FROM news_signal_feature")
			.query(Long.class)
			.single();
	}

	private String titleKeywordsOf(String sourceKey) {
		return jdbcClient.sql("""
			SELECT title_keywords::text
			FROM news_signal_feature
			WHERE source_key = :sourceKey
			""")
			.param("sourceKey", sourceKey)
			.query(String.class)
			.single();
	}

	private String statusOf(Long id) {
		return jdbcClient.sql("""
			SELECT ingest_status
			FROM news_article_observation
			WHERE id = :id
			""")
			.param("id", id)
			.query(String.class)
			.single();
	}
}
