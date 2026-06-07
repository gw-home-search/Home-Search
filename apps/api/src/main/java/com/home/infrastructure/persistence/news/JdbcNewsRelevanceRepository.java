package com.home.infrastructure.persistence.news;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.news.NewsArticleRelevanceCandidate;
import com.home.application.news.NewsArticleRelevanceDecision;
import com.home.application.news.NewsArticleRelevanceRepository;

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcNewsRelevanceRepository implements NewsArticleRelevanceRepository {

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	JdbcNewsRelevanceRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public List<NewsArticleRelevanceCandidate> findUnevaluatedObservedCandidates(int limit, String policyVersion) {
		return jdbcClient.sql("""
			SELECT
			    observation.id,
			    observation.source,
			    observation.source_key,
			    observation.publisher,
			    observation.title,
			    observation.snippet
			FROM news_article_observation observation
			WHERE observation.ingest_status = 'OBSERVED'
			  AND NOT EXISTS (
			      SELECT 1
			      FROM news_relevance_decision decision
			      WHERE decision.article_observation_id = observation.id
			        AND decision.policy_version = :policyVersion
			  )
			ORDER BY observation.first_seen_at, observation.id
			LIMIT :limit
			""")
			.param("policyVersion", policyVersion)
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new NewsArticleRelevanceCandidate(
				resultSet.getLong("id"),
				resultSet.getString("source"),
				resultSet.getString("source_key"),
				resultSet.getString("publisher"),
				resultSet.getString("title"),
				resultSet.getString("snippet")
			))
			.list();
	}

	@Override
	public boolean saveDecisionIfAbsent(NewsArticleRelevanceDecision decision) {
		Long insertedCount = jdbcClient.sql("""
			WITH inserted AS (
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
			        :source,
			        :sourceKey,
			        :policyVersion,
			        :decision,
			        :score,
			        :threshold,
			        :reasonCodes::jsonb,
			        :matchedTerms::jsonb,
			        :evaluatedAt
			    )
			    ON CONFLICT (article_observation_id, policy_version) DO NOTHING
			    RETURNING 1
			)
			SELECT count(*) FROM inserted
			""")
			.param("articleObservationId", decision.articleObservationId())
			.param("source", decision.source())
			.param("sourceKey", decision.sourceKey())
			.param("policyVersion", decision.policyVersion())
			.param("decision", decision.decisionType().name())
			.param("score", decision.score())
			.param("threshold", decision.threshold())
			.param("reasonCodes", json(decision.reasonCodes()))
			.param("matchedTerms", json(decision.matchedTerms()))
			.param("evaluatedAt", decision.evaluatedAt())
			.query(Long.class)
			.single();
		return insertedCount == 1L;
	}

	@Override
	public boolean markSkippedIrrelevantIfObserved(long articleObservationId, String failureReason) {
		return jdbcClient.sql("""
			UPDATE news_article_observation
			SET ingest_status = 'SKIPPED_IRRELEVANT',
			    failure_reason = :failureReason,
			    updated_at = now()
			WHERE id = :articleObservationId
			  AND ingest_status = 'OBSERVED'
			""")
			.param("articleObservationId", articleObservationId)
			.param("failureReason", failureReason)
			.update() == 1;
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize news relevance decision metadata", exception);
		}
	}
}
