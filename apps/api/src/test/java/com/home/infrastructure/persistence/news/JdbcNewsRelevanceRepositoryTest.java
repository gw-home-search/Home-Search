package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleRelevanceCandidate;
import com.home.application.news.NewsArticleRelevanceDecision;
import com.home.application.news.NewsArticleRelevanceDecisionType;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcNewsRelevanceRepositoryTest extends JdbcPostgresTestSupport {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("Jdbc news relevance repository는 observed 미평가 후보만 조회하고 decision을 중복 없이 저장한다")
	void findsUnevaluatedObservedCandidatesAndStoresDecisionOnce() {
		seedSourcePolicy();
		Long observedId = insertArticleObservation("NAVER_NEWS:observed", "OBSERVED", "강남 전세난", "아파트 집값 상승");
		insertArticleObservation("NAVER_NEWS:featured", "FEATURED", "부동산 정책", "이미 feature로 확정");
		JdbcNewsRelevanceRepository repository = new JdbcNewsRelevanceRepository(jdbcClient, objectMapper);

		List<NewsArticleRelevanceCandidate> candidates = repository.findUnevaluatedObservedCandidates(
			10,
			"rule-title-snippet-20260607-r2"
		);

		assertThat(candidates).extracting(NewsArticleRelevanceCandidate::articleObservationId)
			.containsExactly(observedId);

		NewsArticleRelevanceDecision decision = decision(observedId, "NAVER_NEWS:observed");
		assertThat(repository.saveDecisionIfAbsent(decision)).isTrue();
		assertThat(repository.saveDecisionIfAbsent(decision)).isFalse();
		assertThat(decisionCount()).isEqualTo(1L);
		assertThat(repository.findUnevaluatedObservedCandidates(10, "rule-title-snippet-20260607-r2")).isEmpty();
		assertThat(decisionReasonCodes()).contains("REAL_ESTATE_DOMAIN_MATCH");
	}

	@Test
	@DisplayName("Jdbc news relevance repository는 observed row만 skipped irrelevant로 상태 전환한다")
	void marksOnlyObservedRowsAsSkippedIrrelevant() {
		seedSourcePolicy();
		Long observedId = insertArticleObservation("NAVER_NEWS:noise", "OBSERVED", "이 대통령 총리 후보자 지명", "정치 뉴스");
		Long featuredId = insertArticleObservation("NAVER_NEWS:featured", "FEATURED", "강남 재건축", "이미 feature");
		JdbcNewsRelevanceRepository repository = new JdbcNewsRelevanceRepository(jdbcClient, objectMapper);

		assertThat(repository.markSkippedIrrelevantIfObserved(
			observedId,
			"policyVersion=rule-title-snippet-20260607-r2;decision=SKIP_IRRELEVANT;score=0.1000;reasonCodes=[CLEAR_NON_REAL_ESTATE_NOISE]"
		)).isTrue();
		assertThat(repository.markSkippedIrrelevantIfObserved(featuredId, "should not update")).isFalse();

		assertThat(statusOf(observedId)).isEqualTo("SKIPPED_IRRELEVANT");
		assertThat(failureReasonOf(observedId)).contains("CLEAR_NON_REAL_ESTATE_NOISE");
		assertThat(statusOf(featuredId)).isEqualTo("FEATURED");
		assertThat(failureReasonOf(featuredId)).isNull();
	}

	private static NewsArticleRelevanceDecision decision(Long articleObservationId, String sourceKey) {
		return new NewsArticleRelevanceDecision(
			articleObservationId,
			"NAVER_NEWS",
			sourceKey,
			"rule-title-snippet-20260607-r2",
			NewsArticleRelevanceDecisionType.KEEP,
			0.8500,
			0.3500,
			List.of("REAL_ESTATE_DOMAIN_MATCH"),
			Map.of("domain", List.of("전세", "아파트")),
			OffsetDateTime.parse("2026-06-07T00:00:00Z")
		);
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

	private long decisionCount() {
		return jdbcClient.sql("SELECT count(*) FROM news_relevance_decision")
			.query(Long.class)
			.single();
	}

	private String decisionReasonCodes() {
		return jdbcClient.sql("""
			SELECT reason_codes::text
			FROM news_relevance_decision
			""")
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

	private String failureReasonOf(Long id) {
		String value = jdbcClient.sql("""
			SELECT coalesce(failure_reason, '__NULL__')
			FROM news_article_observation
			WHERE id = :id
			""")
			.param("id", id)
			.query(String.class)
			.single();
		return "__NULL__".equals(value) ? null : value;
	}
}
