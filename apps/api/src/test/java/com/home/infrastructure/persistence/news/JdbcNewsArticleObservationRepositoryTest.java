package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.home.application.news.observation.NewsArticleObservationCommand;
import com.home.application.news.observation.NewsArticleObservationStatus;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcNewsArticleObservationRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("Jdbc news observation repository는 metadata-only row를 저장하고 source_key 중복을 막는다")
	void insertsMetadataOnlyObservationAndSkipsDuplicateSourceKey() {
		seedSourcePolicy();
		JdbcNewsArticleObservationRepository repository = new JdbcNewsArticleObservationRepository(jdbcClient);
		NewsArticleObservationCommand command = command("NAVER_NEWS:hash-1");

		assertThat(repository.insertIfAbsent(command)).isTrue();
		assertThat(repository.insertIfAbsent(command)).isFalse();

		assertThat(observationCount()).isEqualTo(1L);
		assertThat(rawProviderPayload()).contains("\"title\": \"강남 재건축\"")
			.doesNotContain("content", "body", "full_text", "html");
	}

	private static NewsArticleObservationCommand command(String sourceKey) {
		return new NewsArticleObservationCommand(
			"NAVER_NEWS",
			sourceKey,
			"example.com",
			"강남 재건축",
			"https://example.com/news/1",
			"https://n.news.naver.com/mnews/article/001/0000000001",
			"서울 강남 재건축",
			OffsetDateTime.parse("2026-06-07T09:30:00+09:00"),
			OffsetDateTime.parse("2026-06-07T09:30:00+09:00"),
			OffsetDateTime.parse("2026-06-07T00:35:00Z"),
			OffsetDateTime.parse("2026-06-07T00:35:00Z"),
			null,
			LocalDate.parse("2026-06-07"),
			"{\"title\":\"강남 재건축\",\"description\":\"서울 강남 재건축\"}",
			"payload-hash",
			NewsArticleObservationStatus.OBSERVED,
			null
		);
	}

	private long observationCount() {
		return jdbcClient.sql("SELECT count(*) FROM news_article_observation")
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

	private String rawProviderPayload() {
		return jdbcClient.sql("""
			SELECT raw_provider_payload::text
			FROM news_article_observation
			WHERE source_key = 'NAVER_NEWS:hash-1'
			""")
			.query(String.class)
			.single();
	}
}
