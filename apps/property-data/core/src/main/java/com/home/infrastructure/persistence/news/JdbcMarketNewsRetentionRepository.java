package com.home.infrastructure.persistence.news;

import com.home.application.news.retention.MarketNewsRetentionRepository;
import com.home.application.news.retention.MarketNewsRetentionResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMarketNewsRetentionRepository implements MarketNewsRetentionRepository {

    private final JdbcClient jdbcClient;

    public JdbcMarketNewsRetentionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    @Transactional
    public MarketNewsRetentionResult deleteExpired(Instant now) {
        int quality = jdbcClient
                .sql("""
                    DELETE FROM market_news_quality_label
                    WHERE COALESCE(reviewed_at, sampled_at) < :cutoff
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(180))))
                .update();
        quality += jdbcClient
                .sql("""
                    DELETE FROM market_news_quality_review_snapshot review_snapshot
                    USING market_news_quality_review_set review_set
                    WHERE review_set.review_set_id = review_snapshot.review_set_id
                      AND review_set.sampled_at < :cutoff
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(180))))
                .update();
        quality += jdbcClient
                .sql("""
                    DELETE FROM market_news_quality_review_set review_set
                    WHERE review_set.sampled_at < :cutoff
                      AND NOT EXISTS (
                          SELECT 1
                          FROM market_news_quality_label label
                          WHERE label.review_set_id = review_set.review_set_id
                      )
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(180))))
                .update();
        int raw = jdbcClient
                .sql("DELETE FROM market_news_raw_item WHERE received_at < :cutoff")
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(7))))
                .update();
        int normalized = jdbcClient
                .sql("""
                    DELETE FROM market_news_snapshot
                    WHERE generated_at < :cutoff
                      AND build_status <> 'PUBLISHED'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM market_news_quality_review_snapshot review_snapshot
                          WHERE review_snapshot.snapshot_id = market_news_snapshot.snapshot_id
                      )
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(30))))
                .update();
        normalized += jdbcClient
                .sql("""
                    DELETE FROM market_news_snapshot_item item
                    USING market_news_article article
                    WHERE article.article_id = item.article_id
                      AND article.provided_at < :cutoff
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(30))))
                .update();
        jdbcClient.sql("""
                    UPDATE market_news_snapshot snapshot
                    SET item_count = (
                        SELECT count(*)
                        FROM market_news_snapshot_item item
                        WHERE item.snapshot_id = snapshot.snapshot_id
                    )
                    WHERE snapshot.item_count <> (
                        SELECT count(*)
                        FROM market_news_snapshot_item item
                        WHERE item.snapshot_id = snapshot.snapshot_id
                    )
                    """).update();
        normalized += jdbcClient
                .sql("""
                    DELETE FROM market_news_relation relation
                    WHERE relation.created_at < :cutoff
                      AND NOT EXISTS (
                          SELECT 1 FROM market_news_snapshot_item item
                          WHERE item.relation_id = relation.relation_id
                      )
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(30))))
                .update();
        normalized += jdbcClient
                .sql("""
                    DELETE FROM market_news_article article
                    WHERE article.last_seen_at < :cutoff
                      AND NOT EXISTS (
                          SELECT 1 FROM market_news_raw_item raw
                          WHERE raw.article_id = article.article_id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM market_news_relation relation
                          WHERE relation.article_id = article.article_id
                      )
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(30))))
                .update();
        jdbcClient
                .sql("""
                    DELETE FROM market_news_major_complex_selection
                    WHERE selected_at < :cutoff
                      AND selection_week < (
                          SELECT COALESCE(MAX(selection_week), DATE '1970-01-01')
                          FROM market_news_major_complex_selection
                          WHERE selection_status = 'PUBLISHED'
                      )
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(180))))
                .update();
        int executionCorrections = jdbcClient
                .sql("""
                    DELETE FROM market_news_execution_aggregate_correction correction
                    USING market_news_collection_execution execution
                    WHERE execution.execution_id = correction.execution_id
                      AND execution.completed_at < :cutoff
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(180))))
                .update();
        executionCorrections += jdbcClient
                .sql("""
                    DELETE FROM market_news_execution_failure_correction correction
                    USING market_news_collection_execution execution
                    WHERE execution.execution_id = correction.execution_id
                      AND execution.completed_at < :cutoff
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(180))))
                .update();
        int workUnits = jdbcClient
                .sql("""
                    DELETE FROM market_news_collection_work_unit unit
                    WHERE unit.completed_at < :cutoff
                      AND NOT EXISTS (
                          SELECT 1 FROM market_news_raw_item raw
                          WHERE raw.work_unit_id = unit.work_unit_id
                      )
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(180))))
                .update();
        int executions = jdbcClient
                .sql("""
                    DELETE FROM market_news_collection_execution execution
                    WHERE execution.completed_at < :cutoff
                      AND NOT EXISTS (
                          SELECT 1 FROM market_news_collection_work_unit unit
                          WHERE unit.execution_id = execution.execution_id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM market_news_snapshot snapshot
                          WHERE snapshot.execution_id = execution.execution_id
                      )
                    """)
                .param("cutoff", utc(now.minus(java.time.Duration.ofDays(180))))
                .update();
        return new MarketNewsRetentionResult(raw, normalized, executionCorrections + workUnits + executions, quality);
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
