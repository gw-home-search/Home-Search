package com.home.infrastructure.persistence.news;

import com.home.application.news.NewsArticleObservationCommand;
import com.home.application.news.NewsArticleObservationRepository;

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcNewsArticleObservationRepository implements NewsArticleObservationRepository {

	private final JdbcClient jdbcClient;

	JdbcNewsArticleObservationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public boolean insertIfAbsent(NewsArticleObservationCommand command) {
		Long insertedCount = jdbcClient.sql("""
			WITH inserted AS (
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
			        updated_at,
			        news_date_kst,
			        raw_provider_payload,
			        payload_hash,
			        ingest_status,
			        failure_reason
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
			        :updatedAt,
			        :newsDateKst,
			        :rawProviderPayload::jsonb,
			        :payloadHash,
			        :ingestStatus,
			        :failureReason
			    )
			    ON CONFLICT (source, source_key) DO NOTHING
			    RETURNING 1
			)
			SELECT count(*) FROM inserted
			""")
			.param("source", command.source())
			.param("sourceKey", command.sourceKey())
			.param("publisher", command.publisher())
			.param("title", command.title())
			.param("url", command.url())
			.param("providerUrl", command.providerUrl())
			.param("snippet", command.snippet())
			.param("publishedAt", command.publishedAt())
			.param("providerPubAt", command.providerPubAt())
			.param("firstSeenAt", command.firstSeenAt())
			.param("collectedAt", command.collectedAt())
			.param("updatedAt", command.updatedAt())
			.param("newsDateKst", command.newsDateKst())
			.param("rawProviderPayload", command.rawProviderPayload())
			.param("payloadHash", command.payloadHash())
			.param("ingestStatus", command.ingestStatus().name())
			.param("failureReason", command.failureReason())
			.query(Long.class)
			.single();
		return insertedCount == 1L;
	}
}
