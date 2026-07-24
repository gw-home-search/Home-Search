package com.home.infrastructure.persistence.insight;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDailyTradeInsightCalculator {

    private final JdbcClient jdbcClient;

    JdbcDailyTradeInsightCalculator(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    void insertItems(UUID executionId) {
        jdbcClient.sql("""
                    WITH candidate AS (
                        SELECT trade.id AS trade_id,
                               trade.deal_date AS trade_deal_date,
                               trade.complex_id,
                               trade.deal_amount,
                               trade.excl_area,
                               raw.processed_at AS disclosed_at,
                               trade.deleted_at,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'si-do' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-do' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-do' THEN region2.code END
                               ) AS sido_code
                        FROM raw_trade_ingest raw
                        JOIN trade ON trade.raw_ingest_id = raw.id
                        JOIN complex ON complex.id = trade.complex_id
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                        WHERE raw.execution_correlation_id = :executionId
                          AND raw.status = 'NORMALIZED'
                    ), scoped AS (
                        SELECT snapshot.snapshot_id,
                               snapshot.data_cutoff,
                               candidate.*,
                               CASE WHEN candidate.deleted_at IS NULL OR candidate.deleted_at > snapshot.data_cutoff
                                    THEN 'ACTIVE' ELSE 'CANCELED' END AS trade_status
                        FROM market_insight_snapshot snapshot
                        JOIN candidate
                          ON snapshot.scope_type = 'NATIONWIDE'
                          OR (snapshot.scope_type = 'SIDO' AND snapshot.region_code = candidate.sido_code)
                        WHERE snapshot.source_execution_id = :executionId
                          AND snapshot.build_status = 'BUILDING'
                    ), metric_rows AS (
                        SELECT snapshot_id,
                               'DAILY_NEW_TRADE'::varchar AS metric_type,
                               row_number() OVER (
                                   PARTITION BY snapshot_id
                                   ORDER BY disclosed_at DESC, trade_id ASC
                               ) AS item_rank,
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                               disclosed_at, deleted_at, trade_status
                        FROM scoped
                        UNION ALL
                        SELECT snapshot_id,
                               'DAILY_HIGHEST_DEAL'::varchar,
                               row_number() OVER (
                                   PARTITION BY snapshot_id
                                   ORDER BY deal_amount DESC, trade_deal_date DESC, trade_id ASC
                               ),
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                               disclosed_at, deleted_at, trade_status
                        FROM scoped
                    )
                    INSERT INTO market_insight_trade_item (
                        snapshot_id, metric_type, rank,
                        trade_id, trade_deal_date, complex_id,
                        deal_amount, excl_area, deal_date, disclosed_at,
                        captured_trade_status, canceled_at
                    )
                    SELECT snapshot_id, metric_type, item_rank,
                           trade_id, trade_deal_date, complex_id,
                           deal_amount, excl_area, trade_deal_date, disclosed_at,
                           trade_status, deleted_at
                    FROM metric_rows
                    WHERE item_rank <= 50
                    """).param("executionId", executionId).update();
    }
}
