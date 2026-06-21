package com.home.news.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.home.domain.news.ArticleDiscoveryStatus;
import com.home.domain.news.CollectionRunMode;
import com.home.domain.news.CollectionRunStatus;
import com.home.domain.news.KeywordCadence;
import com.home.domain.news.NewsKeywordType;
import com.home.domain.news.NewsObservationStatus;
import com.home.domain.news.NewsSource;
import com.home.news.application.ArticleObservationCommand;
import com.home.news.application.ArticleObservationResult;
import com.home.news.application.CollectionRunCounts;
import com.home.news.application.DatasetSignalRow;
import com.home.news.application.SignalFeatureCommand;
import com.home.news.application.SignalFeatureResult;
import com.home.news.application.SignalProfileCommand;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcNewsRepository {

	private final JdbcClient jdbcClient;

	public JdbcNewsRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long upsertManualKeyword(String queryText, NewsKeywordType keywordType) {
		String normalized = normalizeQuery(queryText);
		List<Long> ids = jdbcClient.sql("""
			INSERT INTO news.collection_keyword (
			    query_text,
			    normalized_query_text,
			    keyword_type,
			    cadence
			)
			VALUES (:queryText, :normalizedQueryText, :keywordType, :cadence)
			ON CONFLICT (normalized_query_text, keyword_type, COALESCE(source_table, ''), COALESCE(source_id, 0))
			DO UPDATE SET
			    query_text = EXCLUDED.query_text,
			    updated_at = now()
			RETURNING id
			""")
			.param("queryText", queryText.strip())
			.param("normalizedQueryText", normalized)
			.param("keywordType", keywordType.name())
			.param("cadence", KeywordCadence.MANUAL.name())
			.query(Long.class)
			.list();
		return ids.get(0);
	}

	public long createRun(CollectionRunMode mode, String triggerQueryText, int maxKeywords, int displayLimit, int maxArticles) {
		return jdbcClient.sql("""
			INSERT INTO news.collection_run (
			    run_mode,
			    status,
			    trigger_query_text,
			    max_keywords,
			    display_limit,
			    max_articles
			)
			VALUES (:runMode, :status, :triggerQueryText, :maxKeywords, :displayLimit, :maxArticles)
			RETURNING id
			""")
			.param("runMode", mode.name())
			.param("status", CollectionRunStatus.RUNNING.name())
			.param("triggerQueryText", triggerQueryText)
			.param("maxKeywords", maxKeywords)
			.param("displayLimit", displayLimit)
			.param("maxArticles", maxArticles)
			.query(Long.class)
			.single();
	}

	public long createRunKeyword(long runId, long keywordId, String queryText, NewsKeywordType keywordType, int displayLimit, String sortOrder) {
		return jdbcClient.sql("""
			INSERT INTO news.collection_run_keyword (
			    run_id,
			    keyword_id,
			    query_text,
			    normalized_query_text,
			    keyword_type,
			    display_limit,
			    sort_order,
			    status
			)
			VALUES (
			    :runId,
			    :keywordId,
			    :queryText,
			    :normalizedQueryText,
			    :keywordType,
			    :displayLimit,
			    :sortOrder,
			    :status
			)
			RETURNING id
			""")
			.param("runId", runId)
			.param("keywordId", keywordId)
			.param("queryText", queryText.strip())
			.param("normalizedQueryText", normalizeQuery(queryText))
			.param("keywordType", keywordType.name())
			.param("displayLimit", displayLimit)
			.param("sortOrder", sortOrder)
			.param("status", CollectionRunStatus.RUNNING.name())
			.query(Long.class)
			.single();
	}

	public ArticleObservationResult insertObservationIfAbsent(ArticleObservationCommand command) {
		List<Long> ids = jdbcClient.sql("""
			INSERT INTO news.article_observation (
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
			    :source,
			    :sourceKey,
			    :publisher,
			    :title,
			    :url,
			    :providerUrl,
			    :snippet,
			    :publishedAt,
			    :providerPubAt,
			    :firstSeenAt,
			    :collectedAt,
			    :newsDateKst,
			    CAST(:rawProviderPayload AS jsonb),
			    :payloadHash,
			    :ingestStatus
			)
			ON CONFLICT (source, source_key) DO NOTHING
			RETURNING id
			""")
			.param("source", command.source().name())
			.param("sourceKey", command.sourceKey())
			.param("publisher", command.publisher())
			.param("title", command.title())
			.param("url", command.url())
			.param("providerUrl", command.providerUrl())
			.param("snippet", command.snippet())
			.param("publishedAt", timestamp(command.publishedAt()))
			.param("providerPubAt", timestamp(command.providerPubAt()))
			.param("firstSeenAt", timestamp(command.firstSeenAt()))
			.param("collectedAt", timestamp(command.collectedAt()))
			.param("newsDateKst", command.newsDateKst())
			.param("rawProviderPayload", command.rawProviderPayloadJson())
			.param("payloadHash", command.payloadHash())
			.param("ingestStatus", command.ingestStatus().name())
			.query(Long.class)
			.list();
		if (!ids.isEmpty()) {
			return findObservation(ids.get(0), true);
		}
		return findObservation(command.source(), command.sourceKey(), false);
	}

	public void updateObservationStatus(long observationId, NewsObservationStatus status) {
		jdbcClient.sql("""
			UPDATE news.article_observation
			SET ingest_status = :status,
			    updated_at = now()
			WHERE id = :id
			""")
			.param("status", status.name())
			.param("id", observationId)
			.update();
	}

	public void recordRunArticle(
		long runKeywordId,
		Long articleObservationId,
		NewsSource source,
		String sourceKey,
		int providerRank,
		String titleSnapshot,
		String providerUrlSnapshot,
		ArticleDiscoveryStatus status,
		String failureReason
	) {
		jdbcClient.sql("""
			INSERT INTO news.collection_run_article (
			    run_keyword_id,
			    article_observation_id,
			    source,
			    source_key,
			    provider_rank,
			    title_snapshot,
			    provider_url_snapshot,
			    discovery_status,
			    failure_reason
			)
			VALUES (
			    :runKeywordId,
			    :articleObservationId,
			    :source,
			    :sourceKey,
			    :providerRank,
			    :titleSnapshot,
			    :providerUrlSnapshot,
			    :discoveryStatus,
			    :failureReason
			)
			ON CONFLICT (run_keyword_id, source, source_key)
			WHERE source_key IS NOT NULL
			DO UPDATE SET
			    article_observation_id = COALESCE(EXCLUDED.article_observation_id, news.collection_run_article.article_observation_id),
			    discovery_status = EXCLUDED.discovery_status,
			    failure_reason = EXCLUDED.failure_reason
			""")
			.param("runKeywordId", runKeywordId)
			.param("articleObservationId", articleObservationId)
			.param("source", source.name())
			.param("sourceKey", sourceKey)
			.param("providerRank", providerRank)
			.param("titleSnapshot", titleSnapshot)
			.param("providerUrlSnapshot", providerUrlSnapshot)
			.param("discoveryStatus", status.name())
			.param("failureReason", failureReason)
			.update();
	}

	public void insertSignalProfileIfAbsent(SignalProfileCommand command) {
		jdbcClient.sql("""
			INSERT INTO news.signal_extraction_profile (
			    extraction_version,
			    provider,
			    model,
			    prompt_version,
			    schema_version,
			    prompt_hash,
			    json_schema_hash,
			    active
			)
			VALUES (
			    :extractionVersion,
			    :provider,
			    :model,
			    :promptVersion,
			    :schemaVersion,
			    :promptHash,
			    :jsonSchemaHash,
			    :active
			)
			ON CONFLICT (extraction_version) DO NOTHING
			""")
			.param("extractionVersion", command.extractionVersion())
			.param("provider", command.provider())
			.param("model", command.model())
			.param("promptVersion", command.promptVersion())
			.param("schemaVersion", command.schemaVersion())
			.param("promptHash", command.promptHash())
			.param("jsonSchemaHash", command.jsonSchemaHash())
			.param("active", command.active())
			.update();
	}

	public SignalFeatureResult insertFeatureIfAbsent(SignalFeatureCommand command) {
		List<Long> ids = jdbcClient.sql("""
			INSERT INTO news.signal_feature (
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
			    evidence_level,
			    model,
			    prompt_version,
			    input_hash,
			    structured_output
			)
			VALUES (
			    :articleObservationId,
			    :source,
			    :sourceKey,
			    :featureDateKst,
			    :firstSeenAt,
			    CAST(:regionTags AS jsonb),
			    CAST(:complexCandidates AS jsonb),
			    CAST(:topicTags AS jsonb),
			    :impactTarget,
			    :impactDirection,
			    :sentiment,
			    CAST(:confidence AS numeric),
			    :extractionVersion,
			    :evidenceLevel,
			    :model,
			    :promptVersion,
			    :inputHash,
			    CAST(:structuredOutput AS jsonb)
			)
			ON CONFLICT (source, source_key, extraction_version) DO NOTHING
			RETURNING id
			""")
			.param("articleObservationId", command.articleObservationId())
			.param("source", command.source().name())
			.param("sourceKey", command.sourceKey())
			.param("featureDateKst", command.featureDateKst())
			.param("firstSeenAt", timestamp(command.firstSeenAt()))
			.param("regionTags", command.regionTagsJson())
			.param("complexCandidates", command.complexCandidatesJson())
			.param("topicTags", command.topicTagsJson())
			.param("impactTarget", command.impactTarget())
			.param("impactDirection", command.impactDirection())
			.param("sentiment", command.sentiment())
			.param("confidence", command.confidence())
			.param("extractionVersion", command.extractionVersion())
			.param("evidenceLevel", command.evidenceLevel())
			.param("model", command.model())
			.param("promptVersion", command.promptVersion())
			.param("inputHash", command.inputHash())
			.param("structuredOutput", command.structuredOutputJson())
			.query(Long.class)
			.list();
		if (!ids.isEmpty()) {
			updateObservationStatus(command.articleObservationId(), NewsObservationStatus.FEATURED);
			return new SignalFeatureResult(ids.get(0), true);
		}
		Long id = jdbcClient.sql("""
			SELECT id
			FROM news.signal_feature
			WHERE source = :source
			  AND source_key = :sourceKey
			  AND extraction_version = :extractionVersion
			""")
			.param("source", command.source().name())
			.param("sourceKey", command.sourceKey())
			.param("extractionVersion", command.extractionVersion())
			.query(Long.class)
			.single();
		return new SignalFeatureResult(id, false);
	}

	public boolean hasSignalFeature(NewsSource source, String sourceKey, String extractionVersion) {
		return Boolean.TRUE.equals(jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM news.signal_feature
			    WHERE source = :source
			      AND source_key = :sourceKey
			      AND extraction_version = :extractionVersion
			)
			""")
			.param("source", source.name())
			.param("sourceKey", sourceKey)
			.param("extractionVersion", extractionVersion)
			.query(Boolean.class)
			.single());
	}

	public void updateRunKeywordCounts(
		long runKeywordId,
		CollectionRunStatus status,
		int providerTotal,
		int providerStart,
		int providerDisplay,
		int fetchedCount,
		int observedNewCount,
		int observedDuplicateCount,
		int featureCreatedCount,
		int failedCount,
		String failureReason
	) {
		jdbcClient.sql("""
			UPDATE news.collection_run_keyword
			SET status = :status,
			    provider_total = :providerTotal,
			    provider_start = :providerStart,
			    provider_display = :providerDisplay,
			    fetched_count = :fetchedCount,
			    observed_new_count = :observedNewCount,
			    observed_duplicate_count = :observedDuplicateCount,
			    feature_created_count = :featureCreatedCount,
			    failed_count = :failedCount,
			    failure_reason = :failureReason,
			    finished_at = now()
			WHERE id = :id
			""")
			.param("status", status.name())
			.param("providerTotal", providerTotal)
			.param("providerStart", providerStart)
			.param("providerDisplay", providerDisplay)
			.param("fetchedCount", fetchedCount)
			.param("observedNewCount", observedNewCount)
			.param("observedDuplicateCount", observedDuplicateCount)
			.param("featureCreatedCount", featureCreatedCount)
			.param("failedCount", failedCount)
			.param("failureReason", failureReason)
			.param("id", runKeywordId)
			.update();
	}

	public void finalizeRun(long runId, CollectionRunCounts counts) {
		jdbcClient.sql("""
			UPDATE news.collection_run
			SET status = :status,
			    keyword_count = :keywordCount,
			    provider_item_count = :providerItemCount,
			    observed_new_count = :observedNewCount,
			    observed_duplicate_count = :observedDuplicateCount,
			    feature_created_count = :featureCreatedCount,
			    feature_skipped_count = :featureSkippedCount,
			    failed_count = :failedCount,
			    failure_reason = :failureReason,
			    finished_at = now()
			WHERE id = :id
			""")
			.param("status", counts.status().name())
			.param("keywordCount", counts.keywordCount())
			.param("providerItemCount", counts.providerItemCount())
			.param("observedNewCount", counts.observedNewCount())
			.param("observedDuplicateCount", counts.observedDuplicateCount())
			.param("featureCreatedCount", counts.featureCreatedCount())
			.param("featureSkippedCount", counts.featureSkippedCount())
			.param("failedCount", counts.failedCount())
			.param("failureReason", counts.failureReason())
			.param("id", runId)
			.update();
	}

	public List<DatasetSignalRow> findDatasetRowsBefore(Instant predictionCutoff) {
		return jdbcClient.sql("""
			SELECT
			    source,
			    source_key,
			    publisher,
			    title,
			    url,
			    first_seen_at,
			    feature_date_kst,
			    impact_target,
			    impact_direction,
			    sentiment,
			    confidence,
			    extraction_version,
			    evidence_level
			FROM news.signal_dataset_view
			WHERE first_seen_at <= :predictionCutoff
			ORDER BY first_seen_at, source_key
			""")
			.param("predictionCutoff", timestamp(predictionCutoff))
			.query(this::datasetRow)
			.list();
	}

	public String runStatus(long runId) {
		return jdbcClient.sql("SELECT status FROM news.collection_run WHERE id = :id")
			.param("id", runId)
			.query(String.class)
			.single();
	}

	private ArticleObservationResult findObservation(long id, boolean created) {
		return jdbcClient.sql("""
			SELECT *
			FROM news.article_observation
			WHERE id = :id
			""")
			.param("id", id)
			.query((rs, rowNum) -> observation(rs, created))
			.single();
	}

	private ArticleObservationResult findObservation(NewsSource source, String sourceKey, boolean created) {
		return jdbcClient.sql("""
			SELECT *
			FROM news.article_observation
			WHERE source = :source
			  AND source_key = :sourceKey
			""")
			.param("source", source.name())
			.param("sourceKey", sourceKey)
			.query((rs, rowNum) -> observation(rs, created))
			.single();
	}

	private ArticleObservationResult observation(ResultSet rs, boolean created) throws SQLException {
		return new ArticleObservationResult(
			rs.getLong("id"),
			created,
			NewsSource.valueOf(rs.getString("source")),
			rs.getString("source_key"),
			rs.getString("publisher"),
			rs.getString("title"),
			rs.getString("url"),
			rs.getString("provider_url"),
			rs.getString("snippet"),
			instant(rs, "provider_pub_at"),
			instant(rs, "first_seen_at"),
			rs.getDate("news_date_kst").toLocalDate()
		);
	}

	private DatasetSignalRow datasetRow(ResultSet rs, int rowNum) throws SQLException {
		return new DatasetSignalRow(
			rs.getString("source"),
			rs.getString("source_key"),
			rs.getString("publisher"),
			rs.getString("title"),
			rs.getString("url"),
			instant(rs, "first_seen_at"),
			rs.getDate("feature_date_kst").toLocalDate(),
			rs.getString("impact_target"),
			rs.getString("impact_direction"),
			rs.getString("sentiment"),
			rs.getBigDecimal("confidence"),
			rs.getString("extraction_version"),
			rs.getString("evidence_level")
		);
	}

	private String normalizeQuery(String queryText) {
		return queryText.strip().toLowerCase(Locale.ROOT);
	}

	private Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private Instant instant(ResultSet rs, String columnName) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(columnName);
		return timestamp == null ? null : timestamp.toInstant();
	}
}
