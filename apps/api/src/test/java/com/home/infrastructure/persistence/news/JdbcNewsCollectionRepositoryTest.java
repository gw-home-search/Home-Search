package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import com.home.application.news.collection.NewsCollectionArticleDiscovery;
import com.home.application.news.collection.NewsCollectionKeyword;
import com.home.application.news.collection.NewsCollectionRepository;
import com.home.application.news.collection.NewsCollectionRunCompletion;
import com.home.application.news.collection.NewsCollectionRunKeywordCompletion;
import com.home.domain.news.NewsCollectionArticleDisposition;
import com.home.domain.news.NewsCollectionKeywordCadence;
import com.home.domain.news.NewsCollectionKeywordType;
import com.home.domain.news.NewsCollectionNotificationStatus;
import com.home.domain.news.NewsCollectionRunStatus;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcNewsCollectionRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("Jdbc news collection repository는 due keyword와 run keyword article provenance를 저장한다")
	void storesDueKeywordsAndRunArticleProvenance() {
		seedSourcePolicy();
		Long keywordId = insertKeyword("강남 재건축", 90, "2026-06-10T03:59:00+09:00");
		Long skippedKeywordId = insertDisabledKeyword("분양가 상한제");
		Long articleId = insertObservation("NAVER_NEWS:gangnam-1");
		NewsCollectionRepository repository = new JdbcNewsCollectionRepository(jdbcClient);

		List<NewsCollectionKeyword> dueKeywords = repository.findDueKeywords(
			OffsetDateTime.parse("2026-06-10T04:00:00+09:00"),
			10
		);
		long runId = repository.startRun(OffsetDateTime.parse("2026-06-10T04:00:00+09:00"));
		long runKeywordId = repository.startKeyword(runId, dueKeywords.get(0), OffsetDateTime.parse("2026-06-10T04:00:01+09:00"));
		repository.recordArticles(runKeywordId, List.of(new NewsCollectionArticleDiscovery(
			"NAVER_NEWS",
			"NAVER_NEWS:gangnam-1",
			1,
			NewsCollectionArticleDisposition.OBSERVED
		)));
		repository.completeKeyword(new NewsCollectionRunKeywordCompletion(
			runKeywordId,
			NewsCollectionRunStatus.COMPLETED,
			OffsetDateTime.parse("2026-06-10T04:00:02+09:00"),
			1,
			1,
			0,
			null
		));
		repository.markKeywordCollected(
			keywordId,
			OffsetDateTime.parse("2026-06-10T04:00:02+09:00"),
			OffsetDateTime.parse("2026-06-11T04:00:02+09:00")
		);
		repository.completeRun(new NewsCollectionRunCompletion(
			runId,
			NewsCollectionRunStatus.COMPLETED,
			OffsetDateTime.parse("2026-06-10T04:00:05+09:00"),
			1,
			1,
			1,
			0,
			1,
			1,
			0,
			0,
			1,
			1,
			0,
			"2026-06-10",
			"/tmp/obsidian/news-signals/daily/2026-06-10.md",
			1,
			false,
			NewsCollectionNotificationStatus.SENT,
			null,
			null
		));

		assertThat(dueKeywords).singleElement()
			.extracting(NewsCollectionKeyword::id)
			.isEqualTo(keywordId);
		assertThat(dueKeywords)
			.extracting(NewsCollectionKeyword::id)
			.doesNotContain(skippedKeywordId);
		assertThat(articleProvenanceCount(articleId)).isEqualTo(1L);
		assertThat(keywordStatus(runKeywordId)).isEqualTo("COMPLETED");
		assertThat(runStatus(runId)).isEqualTo("COMPLETED");
		assertThat(keywordNextDueAt(keywordId)).isEqualTo(OffsetDateTime.parse("2026-06-11T04:00:02+09:00"));
	}

	private Long insertKeyword(String queryText, int priority, String nextDueAt) {
		return jdbcClient.sql("""
			INSERT INTO news_collection_keyword (
			    query_text,
			    keyword_type,
			    priority,
			    cadence,
			    enabled,
			    next_due_at
			)
			VALUES (:queryText, 'TOPIC', :priority, 'DAILY', true, :nextDueAt)
			RETURNING id
			""")
			.param("queryText", queryText)
			.param("priority", priority)
			.param("nextDueAt", OffsetDateTime.parse(nextDueAt))
			.query(Long.class)
			.single();
	}

	private Long insertDisabledKeyword(String queryText) {
		return jdbcClient.sql("""
			INSERT INTO news_collection_keyword (
			    query_text,
			    keyword_type,
			    priority,
			    cadence,
			    enabled,
			    next_due_at
			)
			VALUES (:queryText, 'TOPIC', 10, 'DAILY', false, TIMESTAMPTZ '2026-06-10T03:59:00+09:00')
			RETURNING id
			""")
			.param("queryText", queryText)
			.query(Long.class)
			.single();
	}

	private Long insertObservation(String sourceKey) {
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
			    '강남 재건축',
			    'https://example.com/news/1',
			    'https://n.news.naver.com/mnews/article/001/0000000001',
			    '서울 강남 재건축',
			    TIMESTAMPTZ '2026-06-10T03:30:00+09:00',
			    TIMESTAMPTZ '2026-06-10T03:30:00+09:00',
			    TIMESTAMPTZ '2026-06-10T04:00:00+09:00',
			    TIMESTAMPTZ '2026-06-10T04:00:00+09:00',
			    DATE '2026-06-10',
			    '{"title":"강남 재건축"}'::jsonb,
			    'payload-hash',
			    'OBSERVED'
			)
			RETURNING id
			""")
			.param("sourceKey", sourceKey)
			.query(Long.class)
			.single();
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

	private long articleProvenanceCount(Long articleId) {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM news_collection_run_article run_article
			JOIN news_article_observation observation
			  ON observation.source = run_article.source
			 AND observation.source_key = run_article.source_key
			WHERE observation.id = :articleId
			""")
			.param("articleId", articleId)
			.query(Long.class)
			.single();
	}

	private String keywordStatus(long runKeywordId) {
		return jdbcClient.sql("SELECT status FROM news_collection_run_keyword WHERE id = :id")
			.param("id", runKeywordId)
			.query(String.class)
			.single();
	}

	private String runStatus(long runId) {
		return jdbcClient.sql("SELECT status FROM news_collection_run WHERE id = :id")
			.param("id", runId)
			.query(String.class)
			.single();
	}

	private OffsetDateTime keywordNextDueAt(Long keywordId) {
		return jdbcClient.sql("SELECT next_due_at FROM news_collection_keyword WHERE id = :id")
			.param("id", keywordId)
			.query(OffsetDateTime.class)
			.single();
	}
}
