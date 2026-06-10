package com.home.infrastructure.persistence.news;

import java.time.OffsetDateTime;
import java.util.List;

import com.home.application.news.collection.NewsCollectionArticleDiscovery;
import com.home.application.news.collection.NewsCollectionKeyword;
import com.home.application.news.collection.NewsCollectionRepository;
import com.home.application.news.collection.NewsCollectionRunCompletion;
import com.home.application.news.collection.NewsCollectionRunKeywordCompletion;
import com.home.domain.news.NewsCollectionKeywordCadence;
import com.home.domain.news.NewsCollectionKeywordType;

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcNewsCollectionRepository implements NewsCollectionRepository {

	private final JdbcClient jdbcClient;

	JdbcNewsCollectionRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public List<NewsCollectionKeyword> findDueKeywords(OffsetDateTime now, int limit) {
		if (limit < 1) {
			return List.of();
		}
		return jdbcClient.sql("""
			SELECT
			    id,
			    query_text,
			    keyword_type,
			    source_table,
			    source_id,
			    priority,
			    cadence
			FROM news_collection_keyword
			WHERE enabled = true
			  AND next_due_at <= :now
			ORDER BY priority DESC, next_due_at ASC, id ASC
			LIMIT :limit
			""")
			.param("now", now)
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new NewsCollectionKeyword(
				resultSet.getLong("id"),
				resultSet.getString("query_text"),
				NewsCollectionKeywordType.valueOf(resultSet.getString("keyword_type")),
				resultSet.getString("source_table"),
				resultSet.getString("source_id"),
				resultSet.getInt("priority"),
				NewsCollectionKeywordCadence.valueOf(resultSet.getString("cadence"))
			))
			.list();
	}

	@Override
	public long startRun(OffsetDateTime startedAt) {
		return jdbcClient.sql("""
			INSERT INTO news_collection_run (trigger_type, status, started_at)
			VALUES ('SCHEDULED', 'STARTED', :startedAt)
			RETURNING id
			""")
			.param("startedAt", startedAt)
			.query(Long.class)
			.single();
	}

	@Override
	public long startKeyword(long runId, NewsCollectionKeyword keyword, OffsetDateTime startedAt) {
		return jdbcClient.sql("""
			INSERT INTO news_collection_run_keyword (
			    run_id,
			    keyword_id,
			    query_text,
			    keyword_type,
			    source_table,
			    source_id,
			    priority,
			    cadence,
			    status,
			    started_at
			)
			VALUES (
			    :runId,
			    :keywordId,
			    :queryText,
			    :keywordType,
			    :sourceTable,
			    :sourceId,
			    :priority,
			    :cadence,
			    'STARTED',
			    :startedAt
			)
			RETURNING id
			""")
			.param("runId", runId)
			.param("keywordId", keyword.id())
			.param("queryText", keyword.queryText())
			.param("keywordType", keyword.keywordType().name())
			.param("sourceTable", keyword.sourceTable())
			.param("sourceId", keyword.sourceId())
			.param("priority", keyword.priority())
			.param("cadence", keyword.cadence().name())
			.param("startedAt", startedAt)
			.query(Long.class)
			.single();
	}

	@Override
	public void recordArticles(long runKeywordId, List<NewsCollectionArticleDiscovery> discoveries) {
		if (discoveries == null || discoveries.isEmpty()) {
			return;
		}
		for (NewsCollectionArticleDiscovery discovery : discoveries) {
			jdbcClient.sql("""
				INSERT INTO news_collection_run_article (
				    run_keyword_id,
				    source,
				    source_key,
				    provider_rank,
				    disposition
				)
				VALUES (
				    :runKeywordId,
				    :source,
				    :sourceKey,
				    :providerRank,
				    :disposition
				)
				ON CONFLICT (run_keyword_id, source, source_key) DO NOTHING
				""")
				.param("runKeywordId", runKeywordId)
				.param("source", discovery.source())
				.param("sourceKey", discovery.sourceKey())
				.param("providerRank", discovery.providerRank())
				.param("disposition", discovery.disposition().name())
				.update();
		}
	}

	@Override
	public void completeKeyword(NewsCollectionRunKeywordCompletion completion) {
		jdbcClient.sql("""
			UPDATE news_collection_run_keyword
			SET status = :status,
			    finished_at = :finishedAt,
			    read_count = :readCount,
			    observed_count = :observedCount,
			    duplicate_skipped_count = :duplicateSkippedCount,
			    failure_reason = :failureReason
			WHERE id = :id
			""")
			.param("id", completion.runKeywordId())
			.param("status", completion.status().name())
			.param("finishedAt", completion.finishedAt())
			.param("readCount", completion.readCount())
			.param("observedCount", completion.observedCount())
			.param("duplicateSkippedCount", completion.duplicateSkippedCount())
			.param("failureReason", completion.failureReason())
			.update();
	}

	@Override
	public void markKeywordCollected(long keywordId, OffsetDateTime collectedAt, OffsetDateTime nextDueAt) {
		jdbcClient.sql("""
			UPDATE news_collection_keyword
			SET last_collected_at = :collectedAt,
			    next_due_at = :nextDueAt,
			    updated_at = now()
			WHERE id = :id
			""")
			.param("id", keywordId)
			.param("collectedAt", collectedAt)
			.param("nextDueAt", nextDueAt)
			.update();
	}

	@Override
	public void completeRun(NewsCollectionRunCompletion completion) {
		jdbcClient.sql("""
			UPDATE news_collection_run
			SET status = :status,
			    finished_at = :finishedAt,
			    keyword_count = :keywordCount,
			    read_count = :readCount,
			    observed_count = :observedCount,
			    duplicate_skipped_count = :duplicateSkippedCount,
			    relevance_evaluated_count = :relevanceEvaluatedCount,
			    relevance_kept_count = :relevanceKeptCount,
			    relevance_skipped_irrelevant_count = :relevanceSkippedIrrelevantCount,
			    extraction_evaluated_count = :extractionEvaluatedCount,
			    extracted_count = :extractedCount,
			    duplicate_feature_skipped_count = :duplicateFeatureSkippedCount,
			    export_date = :exportDate,
			    export_path = :exportPath,
			    export_feature_count = :exportFeatureCount,
			    export_article_count = :exportArticleCount,
			    export_truncated = :exportTruncated,
			    notification_status = :notificationStatus,
			    notification_failure_reason = :notificationFailureReason,
			    failure_reason = :failureReason
			WHERE id = :id
			""")
			.param("id", completion.runId())
			.param("status", completion.status().name())
			.param("finishedAt", completion.finishedAt())
			.param("keywordCount", completion.keywordCount())
			.param("readCount", completion.readCount())
			.param("observedCount", completion.observedCount())
			.param("duplicateSkippedCount", completion.duplicateSkippedCount())
			.param("relevanceEvaluatedCount", completion.relevanceEvaluatedCount())
			.param("relevanceKeptCount", completion.relevanceKeptCount())
			.param("relevanceSkippedIrrelevantCount", completion.relevanceSkippedIrrelevantCount())
			.param("extractionEvaluatedCount", completion.extractionEvaluatedCount())
			.param("extractedCount", completion.extractedCount())
			.param("duplicateFeatureSkippedCount", completion.duplicateFeatureSkippedCount())
			.param("exportDate", completion.exportLocalDate())
			.param("exportPath", completion.exportPath())
			.param("exportFeatureCount", completion.exportFeatureCount())
			.param("exportArticleCount", completion.exportArticleCount())
			.param("exportTruncated", completion.exportTruncated())
			.param("notificationStatus", completion.notificationStatus().name())
			.param("notificationFailureReason", completion.notificationFailureReason())
			.param("failureReason", completion.failureReason())
			.update();
	}
}
