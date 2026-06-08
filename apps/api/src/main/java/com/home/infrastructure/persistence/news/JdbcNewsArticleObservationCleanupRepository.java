package com.home.infrastructure.persistence.news;

import java.time.OffsetDateTime;
import java.util.List;

import com.home.application.news.observation.NewsArticleObservationCleanupRecord;
import com.home.application.news.observation.NewsArticleObservationCleanupRepository;

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcNewsArticleObservationCleanupRepository implements NewsArticleObservationCleanupRepository {

	private final JdbcClient jdbcClient;

	JdbcNewsArticleObservationCleanupRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public List<NewsArticleObservationCleanupRecord> purgeProviderPayloads(OffsetDateTime retentionCutoff) {
		return jdbcClient.sql("""
			SELECT
			    article_observation_id,
			    source,
			    source_key,
			    ingest_status,
			    purge_action
			FROM purge_news_article_observation_payloads(:retentionCutoff)
			ORDER BY article_observation_id
			""")
			.param("retentionCutoff", retentionCutoff)
			.query((resultSet, rowNumber) -> new NewsArticleObservationCleanupRecord(
				resultSet.getLong("article_observation_id"),
				resultSet.getString("source"),
				resultSet.getString("source_key"),
				resultSet.getString("ingest_status"),
				resultSet.getString("purge_action")
			))
			.list();
	}
}
