package com.home.infrastructure.persistence.news;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.relevance.NewsArticleRelevanceDecisionType;
import com.home.application.news.signal.NewsSignalFeatureCommand;
import com.home.application.news.signal.NewsSignalFeatureExtractionCandidate;
import com.home.application.news.signal.NewsSignalFeatureExtractionRepository;

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcNewsSignalFeatureExtractionRepository implements NewsSignalFeatureExtractionRepository {

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	JdbcNewsSignalFeatureExtractionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public List<NewsSignalFeatureExtractionCandidate> findPendingCandidates(int limit, String extractionVersion) {
		if (limit < 1) {
			return List.of();
		}
		return jdbcClient.sql("""
			WITH latest_decision AS (
			    SELECT DISTINCT ON (decision.article_observation_id)
			        decision.article_observation_id,
			        decision.decision
			    FROM news_relevance_decision decision
			    ORDER BY decision.article_observation_id, decision.evaluated_at DESC, decision.id DESC
			)
			SELECT
			    observation.id,
			    observation.source,
			    observation.source_key,
			    observation.publisher,
			    observation.title,
			    coalesce(observation.snippet, '') AS snippet,
			    observation.news_date_kst,
			    observation.first_seen_at,
			    latest_decision.decision
			FROM news_article_observation observation
			JOIN latest_decision
			  ON latest_decision.article_observation_id = observation.id
			WHERE observation.ingest_status IN ('OBSERVED', 'FEATURED')
			  AND latest_decision.decision IN ('KEEP', 'REVIEW')
			  AND NOT EXISTS (
			      SELECT 1
			      FROM news_signal_feature feature
			      WHERE feature.article_observation_id = observation.id
			        AND feature.extraction_version = :extractionVersion
			  )
			ORDER BY observation.first_seen_at, observation.id
			LIMIT :limit
			""")
			.param("extractionVersion", extractionVersion)
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new NewsSignalFeatureExtractionCandidate(
				resultSet.getLong("id"),
				resultSet.getString("source"),
				resultSet.getString("source_key"),
				resultSet.getString("publisher"),
				resultSet.getString("title"),
				resultSet.getString("snippet"),
				resultSet.getObject("news_date_kst", LocalDate.class),
				resultSet.getObject("first_seen_at", OffsetDateTime.class),
				NewsArticleRelevanceDecisionType.valueOf(resultSet.getString("decision"))
			))
			.list();
	}

	@Override
	public boolean saveFeatureIfAbsent(NewsSignalFeatureCommand command) {
		Long insertedCount = jdbcClient.sql("""
			WITH inserted AS (
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
			        :source,
			        :sourceKey,
			        :featureDateKst,
			        :firstSeenAt,
			        :titleKeywords::jsonb,
			        :regionTags::jsonb,
			        :complexCandidates::jsonb,
			        :topicTags::jsonb,
			        :impactTarget,
			        :impactDirection,
			        :sentiment,
			        :confidence,
			        :extractionVersion,
			        :evidenceLevel
			    )
			    ON CONFLICT (article_observation_id, extraction_version) DO NOTHING
			    RETURNING 1
			)
			SELECT count(*) FROM inserted
			""")
			.param("articleObservationId", command.articleObservationId())
			.param("source", command.source())
			.param("sourceKey", command.sourceKey())
			.param("featureDateKst", command.featureDateKst())
			.param("firstSeenAt", command.firstSeenAt())
			.param("titleKeywords", json(command.titleKeywords()))
			.param("regionTags", json(command.regionTags()))
			.param("complexCandidates", json(command.complexCandidates()))
			.param("topicTags", json(command.topicTags()))
			.param("impactTarget", command.impactTarget())
			.param("impactDirection", command.impactDirection())
			.param("sentiment", command.sentiment())
			.param("confidence", command.confidence())
			.param("extractionVersion", command.extractionVersion())
			.param("evidenceLevel", command.evidenceLevel())
			.query(Long.class)
			.single();
		return insertedCount == 1L;
	}

	@Override
	public boolean markFeaturedIfObserved(long articleObservationId) {
		return jdbcClient.sql("""
			UPDATE news_article_observation
			SET ingest_status = 'FEATURED',
			    updated_at = now()
			WHERE id = :articleObservationId
			  AND ingest_status = 'OBSERVED'
			""")
			.param("articleObservationId", articleObservationId)
			.update() == 1;
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize news signal feature metadata", exception);
		}
	}
}
