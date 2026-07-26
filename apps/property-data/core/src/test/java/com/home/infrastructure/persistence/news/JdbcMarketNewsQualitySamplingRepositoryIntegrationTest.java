package com.home.infrastructure.persistence.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.domain.news.MarketNewsQualityReviewStatus;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcMarketNewsQualitySamplingRepositoryIntegrationTest extends JdbcPostgresTestSupport {

    private static final UUID EXECUTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174740");
    private static final UUID SNAPSHOT_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174741");
    private static final UUID REVIEW_SET_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174742");
    private static final Instant NOW = Instant.parse("2026-07-24T09:30:00Z");

    @BeforeEach
    void seedPublishedNews() {
        seedPropertyExplorationData();
        jdbcClient.sql("UPDATE complex SET trade_name = '홈' WHERE id = 501").update();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_collection_execution (
                        execution_id, request_id, execution_type, policy_version,
                        scheduled_at, state, call_budget, started_at, completed_at
                    ) VALUES (
                        :executionId, 'NEWS-QUALITY-SAMPLE', 'GENERAL', 'NEWS_V2',
                        :scheduledAt, 'COMPLETED', 4000, :startedAt, :completedAt
                    )
                    """)
                .param("executionId", EXECUTION_ID)
                .param("scheduledAt", NOW.atOffset(ZoneOffset.UTC))
                .param("startedAt", NOW.minusSeconds(60).atOffset(ZoneOffset.UTC))
                .param("completedAt", NOW.atOffset(ZoneOffset.UTC))
                .update();
        long articleId = jdbcClient
                .sql("""
                    INSERT INTO market_news_article (
                        provider, canonical_url_hash, public_url, title, provided_at,
                        first_seen_at, last_seen_at
                    ) VALUES (
                        'NAVER', repeat('e', 64), 'https://news.example.test/quality',
                        'Sample Apartment 거래 가격', :providedAt, :seenAt, :seenAt
                    )
                    RETURNING article_id
                    """)
                .param("providedAt", NOW.minusSeconds(300).atOffset(ZoneOffset.UTC))
                .param("seenAt", NOW.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
        long relationId = jdbcClient
                .sql("""
                    INSERT INTO market_news_relation (
                        article_id, policy_version, category, relation_type,
                        region_code, complex_id, matched_tokens
                    ) VALUES (
                        :articleId, 'NEWS_V2', 'TRANSACTION_PRICE', 'DIRECT_COMPLEX',
                        '11', 501, ARRAY['Sample Apartment']
                    )
                    RETURNING relation_id
                    """)
                .param("articleId", articleId)
                .query(Long.class)
                .single();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot (
                        snapshot_id, execution_id, policy_version, scope_type, region_code,
                        build_status, generated_at, data_cutoff, item_count
                    ) VALUES (
                        :snapshotId, :executionId, 'NEWS_V2', 'SIDO', '11',
                        'PUBLISHED', :generatedAt, :dataCutoff, 1
                    )
                    """)
                .param("snapshotId", SNAPSHOT_ID)
                .param("executionId", EXECUTION_ID)
                .param("generatedAt", NOW.atOffset(ZoneOffset.UTC))
                .param("dataCutoff", NOW.minusSeconds(60).atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot_item (
                        snapshot_id, article_id, relation_id, category, sort_rank, provider_rank
                    ) VALUES (
                        :snapshotId, :articleId, :relationId, 'TRANSACTION_PRICE', 1, 1
                    )
                    """)
                .param("snapshotId", SNAPSHOT_ID)
                .param("articleId", articleId)
                .param("relationId", relationId)
                .update();
    }

    @Test
    @DisplayName("결정적 품질 표본을 멱등 저장하고 부족한 coverage를 기록한다")
    void storesIdempotentDeterministicSampleAndInsufficientCoverage() {
        JdbcMarketNewsQualitySamplingRepository repository = new JdbcMarketNewsQualitySamplingRepository(jdbcClient);

        var first = repository.createDeterministicSample(REVIEW_SET_ID, "NEWS_V2");
        replacePublishedSnapshot();
        var repeated = repository.createDeterministicSample(REVIEW_SET_ID, "NEWS_V2");

        assertThat(first.status()).isEqualTo(MarketNewsQualityReviewStatus.INSUFFICIENT_SAMPLE);
        assertThat(first.totalSampleCount()).isEqualTo(1);
        assertThat(first.directComplexCount()).isEqualTo(1);
        assertThat(first.complexChallengeCount()).isEqualTo(1);
        assertThat(repeated).isEqualTo(first);
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM market_news_quality_label WHERE review_set_id = :reviewSetId")
                        .param("reviewSetId", REVIEW_SET_ID)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                            SELECT has_table_privilege(
                                'home_search_property_runtime',
                                'public.market_news_quality_review_set',
                                'SELECT'
                            )
                            """).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> repository.createDeterministicSample(REVIEW_SET_ID, "NEWS_V1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("policyVersion");
    }

    private void replacePublishedSnapshot() {
        jdbcClient.sql("""
                    UPDATE market_news_snapshot
                    SET build_status = 'WITHDRAWN', withdrawn_reason = 'UNSAFE_PUBLIC_ITEM'
                    WHERE snapshot_id = :snapshotId
                    """).param("snapshotId", SNAPSHOT_ID).update();
        long articleId = jdbcClient
                .sql("""
                    INSERT INTO market_news_article (
                        provider, canonical_url_hash, public_url, title, provided_at,
                        first_seen_at, last_seen_at
                    ) VALUES (
                        'NAVER', repeat('f', 64), 'https://news.example.test/replacement',
                        'Replacement Apartment 거래 가격', :providedAt, :seenAt, :seenAt
                    )
                    RETURNING article_id
                    """)
                .param("providedAt", NOW.minusSeconds(120).atOffset(ZoneOffset.UTC))
                .param("seenAt", NOW.plusSeconds(60).atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
        long relationId = jdbcClient
                .sql("""
                    INSERT INTO market_news_relation (
                        article_id, policy_version, category, relation_type,
                        region_code, complex_id, matched_tokens
                    ) VALUES (
                        :articleId, 'NEWS_V2', 'TRANSACTION_PRICE', 'DIRECT_COMPLEX',
                        '11', 501, ARRAY['Replacement Apartment']
                    )
                    RETURNING relation_id
                    """)
                .param("articleId", articleId)
                .query(Long.class)
                .single();
        UUID replacementSnapshotId = UUID.fromString("123e4567-e89b-12d3-a456-426614174743");
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot (
                        snapshot_id, execution_id, policy_version, scope_type, region_code,
                        build_status, generated_at, data_cutoff, item_count
                    ) VALUES (
                        :snapshotId, :executionId, 'NEWS_V2', 'SIDO', '11',
                        'PUBLISHED', :generatedAt, :dataCutoff, 1
                    )
                    """)
                .param("snapshotId", replacementSnapshotId)
                .param("executionId", EXECUTION_ID)
                .param("generatedAt", NOW.plusSeconds(60).atOffset(ZoneOffset.UTC))
                .param("dataCutoff", NOW.atOffset(ZoneOffset.UTC))
                .update();
        jdbcClient
                .sql("""
                    INSERT INTO market_news_snapshot_item (
                        snapshot_id, article_id, relation_id, category, sort_rank, provider_rank
                    ) VALUES (
                        :snapshotId, :articleId, :relationId, 'TRANSACTION_PRICE', 1, 1
                    )
                    """)
                .param("snapshotId", replacementSnapshotId)
                .param("articleId", articleId)
                .param("relationId", relationId)
                .update();
    }
}
