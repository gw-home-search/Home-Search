package com.home.infrastructure.persistence.insight;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcWeeklyTradeInsightCalculator {

    private final JdbcClient jdbcClient;

    JdbcWeeklyTradeInsightCalculator(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    void insertItems(LocalDate weekStart) {
        insertNewAndHighest(weekStart);
        insertExactAreaComparisons(weekStart);
        insertCancellations(weekStart);
    }

    private void insertNewAndHighest(LocalDate weekStart) {
        jdbcClient.sql("""
                    WITH candidate AS (
                        SELECT snapshot.snapshot_id, snapshot.data_cutoff,
                               trade.id AS trade_id, trade.deal_date AS trade_deal_date,
                               trade.complex_id, trade.deal_amount, trade.excl_area,
                               raw.processed_at AS disclosed_at, trade.deleted_at
                        FROM market_insight_snapshot snapshot
                        JOIN market_insight_snapshot_execution lineage
                          ON lineage.snapshot_id = snapshot.snapshot_id
                        JOIN raw_trade_ingest raw
                          ON raw.execution_correlation_id = lineage.execution_id
                         AND raw.status = 'NORMALIZED'
                        JOIN trade ON trade.raw_ingest_id = raw.id
                        JOIN complex ON complex.id = trade.complex_id
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                        WHERE snapshot.period_type = 'WEEKLY'
                          AND snapshot.period_start = :weekStart
                          AND snapshot.build_status = 'BUILDING'
                          AND (snapshot.scope_type = 'NATIONWIDE'
                               OR snapshot.region_code = COALESCE(
                                   CASE WHEN region0.region_type = 'si-do' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-do' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-do' THEN region2.code END))
                    ), metric_rows AS (
                        SELECT snapshot_id, 'WEEKLY_NEW_TRADE'::varchar AS metric_type,
                               row_number() OVER (PARTITION BY snapshot_id
                                   ORDER BY disclosed_at DESC, trade_id ASC) AS item_rank,
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                               disclosed_at, deleted_at, data_cutoff
                        FROM candidate
                        UNION ALL
                        SELECT snapshot_id, 'WEEKLY_HIGHEST_DEAL'::varchar,
                               row_number() OVER (PARTITION BY snapshot_id
                                   ORDER BY deal_amount DESC, trade_deal_date DESC, trade_id ASC),
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                               disclosed_at, deleted_at, data_cutoff
                        FROM candidate
                    )
                    INSERT INTO market_insight_trade_item (
                        snapshot_id, metric_type, rank, trade_id, trade_deal_date, complex_id,
                        deal_amount, excl_area, deal_date, disclosed_at,
                        captured_trade_status, canceled_at
                    )
                    SELECT snapshot_id, metric_type, item_rank, trade_id, trade_deal_date, complex_id,
                           deal_amount, excl_area, trade_deal_date, disclosed_at,
                           CASE WHEN deleted_at IS NULL OR deleted_at > data_cutoff
                                THEN 'ACTIVE' ELSE 'CANCELED' END,
                           deleted_at
                    FROM metric_rows WHERE item_rank <= 50
                    """).param("weekStart", weekStart).update();
    }

    private void insertExactAreaComparisons(LocalDate weekStart) {
        jdbcClient.sql("""
                    WITH candidates AS (
                        SELECT snapshot.snapshot_id, snapshot.data_cutoff,
                               trade.id AS trade_id, trade.deal_date AS trade_deal_date,
                               trade.complex_id, trade.deal_amount, trade.excl_area,
                               raw.processed_at AS disclosed_at
                        FROM market_insight_snapshot snapshot
                        JOIN market_insight_snapshot_execution lineage
                          ON lineage.snapshot_id = snapshot.snapshot_id
                        JOIN raw_trade_ingest raw
                          ON raw.execution_correlation_id = lineage.execution_id
                         AND raw.status = 'NORMALIZED'
                        JOIN trade ON trade.raw_ingest_id = raw.id
                        JOIN complex ON complex.id = trade.complex_id
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                        WHERE snapshot.period_type = 'WEEKLY'
                          AND snapshot.period_start = :weekStart
                          AND snapshot.build_status = 'BUILDING'
                          AND (trade.deleted_at IS NULL OR trade.deleted_at > snapshot.data_cutoff)
                          AND (snapshot.scope_type = 'NATIONWIDE'
                               OR snapshot.region_code = COALESCE(
                                   CASE WHEN region0.region_type = 'si-do' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-do' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-do' THEN region2.code END))
                    ), calculated AS (
                        SELECT candidate.*,
                               record_previous.deal_amount AS record_previous_amount,
                               record_previous.deal_date AS record_previous_date,
                               previous_date.previous_amount,
                               previous_date.previous_deal_date,
                               previous_date.sample_count,
                               candidate.deal_amount - previous_date.previous_amount AS previous_delta_amount,
                               round((candidate.deal_amount - previous_date.previous_amount)::numeric
                                   * 100 / previous_date.previous_amount, 6) AS previous_delta_rate
                        FROM candidates candidate
                        JOIN LATERAL (
                            SELECT history.deal_amount, history.deal_date
                            FROM trade history
                            WHERE history.complex_id = candidate.complex_id
                              AND history.excl_area = candidate.excl_area
                              AND history.deal_date < candidate.trade_deal_date
                              AND (history.deleted_at IS NULL OR history.deleted_at > candidate.data_cutoff)
                            ORDER BY history.deal_amount DESC, history.deal_date DESC, history.id ASC
                            LIMIT 1
                        ) record_previous ON TRUE
                        JOIN LATERAL (
                            SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY history.deal_amount) AS previous_amount,
                                   history.deal_date AS previous_deal_date,
                                   count(*)::integer AS sample_count
                            FROM trade history
                            WHERE history.complex_id = candidate.complex_id
                              AND history.excl_area = candidate.excl_area
                              AND history.deal_date = (
                                  SELECT max(previous.deal_date) FROM trade previous
                                  WHERE previous.complex_id = candidate.complex_id
                                    AND previous.excl_area = candidate.excl_area
                                    AND previous.deal_date < candidate.trade_deal_date
                                    AND (previous.deleted_at IS NULL OR previous.deleted_at > candidate.data_cutoff))
                              AND (history.deleted_at IS NULL OR history.deleted_at > candidate.data_cutoff)
                            GROUP BY history.deal_date
                        ) previous_date ON TRUE
                    ), metric_rows AS (
                        SELECT snapshot_id, 'AREA_RECORD_HIGH'::varchar AS metric_type,
                               row_number() OVER (PARTITION BY snapshot_id
                                   ORDER BY deal_amount - record_previous_amount DESC,
                                   round((deal_amount - record_previous_amount)::numeric * 100
                                       / record_previous_amount, 6) DESC, trade_id ASC) AS item_rank,
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area, disclosed_at,
                               record_previous_amount AS previous_amount,
                               record_previous_date AS previous_deal_date,
                               deal_amount - record_previous_amount AS delta_amount,
                               round((deal_amount - record_previous_amount)::numeric * 100
                                   / record_previous_amount, 6) AS delta_rate, sample_count
                        FROM calculated WHERE deal_amount > record_previous_amount
                        UNION ALL
                        SELECT snapshot_id, 'AREA_PREVIOUS_RISE'::varchar,
                               row_number() OVER (PARTITION BY snapshot_id
                                   ORDER BY previous_delta_rate DESC, trade_id ASC),
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area, disclosed_at,
                               previous_amount, previous_deal_date, previous_delta_amount,
                               previous_delta_rate, sample_count
                        FROM calculated WHERE previous_delta_amount > 0
                        UNION ALL
                        SELECT snapshot_id, 'AREA_PREVIOUS_FALL'::varchar,
                               row_number() OVER (PARTITION BY snapshot_id
                                   ORDER BY previous_delta_rate ASC, trade_id ASC),
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area, disclosed_at,
                               previous_amount, previous_deal_date, previous_delta_amount,
                               previous_delta_rate, sample_count
                        FROM calculated WHERE previous_delta_amount < 0
                    )
                    INSERT INTO market_insight_trade_item (
                        snapshot_id, metric_type, rank, trade_id, trade_deal_date, complex_id,
                        deal_amount, excl_area, deal_date, disclosed_at,
                        previous_amount, previous_deal_date, delta_amount, delta_rate,
                        comparison_sample_count, captured_trade_status
                    )
                    SELECT snapshot_id, metric_type, item_rank, trade_id, trade_deal_date, complex_id,
                           deal_amount, excl_area, trade_deal_date, disclosed_at,
                           previous_amount, previous_deal_date, delta_amount, delta_rate,
                           sample_count, 'ACTIVE'
                    FROM metric_rows WHERE item_rank <= 50
                    """).param("weekStart", weekStart).update();
    }

    private void insertCancellations(LocalDate weekStart) {
        jdbcClient.sql("""
                    WITH canceled AS (
                        SELECT snapshot.snapshot_id, trade.id AS trade_id,
                               trade.deal_date AS trade_deal_date, trade.complex_id,
                               trade.deal_amount, trade.excl_area,
                               min(raw.processed_at) AS disclosed_at, trade.deleted_at AS canceled_at
                        FROM market_insight_snapshot snapshot
                        JOIN market_insight_snapshot_execution lineage
                          ON lineage.snapshot_id = snapshot.snapshot_id
                        JOIN rtms_collection_execution execution
                          ON execution.execution_id = lineage.execution_id
                        JOIN raw_trade_ingest raw
                          ON raw.execution_correlation_id = execution.execution_id
                         AND raw.status = 'CANCELED'
                        JOIN trade_source_key_registry registry
                          ON registry.source = raw.source AND registry.source_key = raw.source_key
                        JOIN trade ON trade.id = registry.trade_id
                                  AND trade.deal_date = registry.trade_deal_date
                        JOIN complex ON complex.id = trade.complex_id
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                        WHERE snapshot.period_type = 'WEEKLY'
                          AND snapshot.period_start = :weekStart
                          AND snapshot.build_status = 'BUILDING'
                          AND trade.deleted_at >= execution.started_at
                          AND trade.deleted_at <= execution.completed_at
                          AND (snapshot.scope_type = 'NATIONWIDE'
                               OR snapshot.region_code = COALESCE(
                                   CASE WHEN region0.region_type = 'si-do' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-do' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-do' THEN region2.code END))
                        GROUP BY snapshot.snapshot_id, trade.id, trade.deal_date,
                                 trade.complex_id, trade.deal_amount, trade.excl_area, trade.deleted_at
                    ), ranked AS (
                        SELECT canceled.*, row_number() OVER (PARTITION BY snapshot_id
                            ORDER BY canceled_at DESC, trade_id ASC) AS item_rank
                        FROM canceled
                    )
                    INSERT INTO market_insight_trade_item (
                        snapshot_id, metric_type, rank, trade_id, trade_deal_date, complex_id,
                        deal_amount, excl_area, deal_date, disclosed_at,
                        captured_trade_status, canceled_at
                    )
                    SELECT snapshot_id, 'CANCELLATION_CORRECTION', item_rank,
                           trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                           trade_deal_date, disclosed_at, 'CANCELED', canceled_at
                    FROM ranked WHERE item_rank <= 50
                    """).param("weekStart", weekStart).update();
    }
}
