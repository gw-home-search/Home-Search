package com.home.infrastructure.persistence.insight;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCancellationInsightCalculator {

    private final JdbcClient jdbcClient;

    JdbcCancellationInsightCalculator(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    void insertItems(UUID executionId) {
        jdbcClient.sql("""
                    WITH canceled_trade AS (
                        SELECT snapshot.snapshot_id,
                               trade.id AS trade_id,
                               trade.deal_date AS trade_deal_date,
                               trade.complex_id,
                               trade.deal_amount,
                               trade.excl_area,
                               min(raw.processed_at) AS disclosed_at,
                               trade.deleted_at AS canceled_at
                        FROM rtms_collection_execution execution
                        JOIN raw_trade_ingest raw
                          ON raw.execution_correlation_id = execution.execution_id
                         AND raw.status = 'CANCELED'
                        JOIN trade_source_key_registry registry
                          ON registry.source = raw.source
                         AND registry.source_key = raw.source_key
                        JOIN trade
                          ON trade.id = registry.trade_id
                         AND trade.deal_date = registry.trade_deal_date
                        JOIN complex ON complex.id = trade.complex_id
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                        JOIN market_insight_snapshot snapshot
                          ON snapshot.source_execution_id = execution.execution_id
                         AND snapshot.build_status = 'BUILDING'
                         AND (
                             snapshot.scope_type = 'NATIONWIDE'
                             OR snapshot.region_code = COALESCE(
                                 CASE WHEN region0.region_type = 'si-do' THEN region0.code END,
                                 CASE WHEN region1.region_type = 'si-do' THEN region1.code END,
                                 CASE WHEN region2.region_type = 'si-do' THEN region2.code END
                             )
                         )
                        WHERE execution.execution_id = :executionId
                          AND trade.deleted_at >= execution.started_at
                          AND trade.deleted_at <= execution.completed_at
                        GROUP BY snapshot.snapshot_id, trade.id, trade.deal_date,
                                 trade.complex_id, trade.deal_amount, trade.excl_area, trade.deleted_at
                    ), ranked AS (
                        SELECT canceled_trade.*,
                               row_number() OVER (
                                   PARTITION BY snapshot_id
                                   ORDER BY canceled_at DESC, trade_id ASC
                               ) AS item_rank
                        FROM canceled_trade
                    )
                    INSERT INTO market_insight_trade_item (
                        snapshot_id, metric_type, rank,
                        trade_id, trade_deal_date, complex_id,
                        deal_amount, excl_area, deal_date, disclosed_at,
                        captured_trade_status, canceled_at
                    )
                    SELECT snapshot_id, 'CANCELLATION_CORRECTION', item_rank,
                           trade_id, trade_deal_date, complex_id,
                           deal_amount, excl_area, trade_deal_date, disclosed_at,
                           'CANCELED', canceled_at
                    FROM ranked
                    WHERE item_rank <= 50
                    """).param("executionId", executionId).update();
    }
}
