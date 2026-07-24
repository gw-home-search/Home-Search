package com.home.infrastructure.persistence.insight;

import com.home.domain.insight.MarketInsightRankingPolicy;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRolling7dTradeInsightCalculator {

    private static final MarketInsightRankingPolicy RANKING_POLICY = MarketInsightRankingPolicy.rollingSevenDay();

    private final JdbcClient jdbcClient;

    JdbcRolling7dTradeInsightCalculator(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    void insertItems(UUID executionId) {
        jdbcClient.sql("SET LOCAL max_parallel_workers_per_gather = 0").update();
        updateQuality(executionId);
        insertNewAndHighest(executionId);
        insertExactAreaComparisons(executionId);
        insertCancellations(executionId);
    }

    private void updateQuality(UUID executionId) {
        jdbcClient.sql("""
                    WITH source_identity AS (
                        SELECT raw.source, raw.source_key,
                               max(raw.registration_date) AS registration_date,
                               max(raw.cancellation_date) AS cancellation_date,
                               bool_or(raw.registration_date_raw IS NOT NULL) AS has_registration_raw,
                               bool_or(raw.cancellation_date_raw IS NOT NULL) AS has_cancellation_raw,
                               bool_or(raw.status = 'CANCELED') AS is_cancellation
                        FROM raw_trade_ingest raw
                        WHERE raw.execution_correlation_id = :executionId
                        GROUP BY raw.source, raw.source_key
                    ), linked AS (
                        SELECT identity.*,
                               trade.deal_date AS trade_deal_date,
                               trade.deleted_at,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'si-do' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-do' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-do' THEN region2.code END
                               ) AS sido_code
                        FROM source_identity identity
                        JOIN trade_source_key_registry registry
                          ON registry.source = identity.source
                         AND registry.source_key = identity.source_key
                        JOIN trade
                          ON trade.id = registry.trade_id
                         AND trade.deal_date = registry.trade_deal_date
                        JOIN complex ON complex.id = trade.complex_id
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                    ), quality AS (
                        SELECT snapshot.snapshot_id,
                               count(*) FILTER (
                                   WHERE linked.registration_date IS NULL
                                     AND NOT linked.has_registration_raw
                                     AND NOT linked.is_cancellation
                                     AND linked.trade_deal_date
                                         BETWEEN snapshot.period_start AND snapshot.period_end
                                     AND (linked.deleted_at IS NULL
                                          OR linked.deleted_at > snapshot.data_cutoff))::integer
                                   AS missing_registration,
                               count(*) FILTER (
                                   WHERE linked.registration_date IS NULL
                                     AND linked.has_registration_raw
                                     AND NOT linked.is_cancellation
                                     AND linked.trade_deal_date
                                         BETWEEN snapshot.period_start AND snapshot.period_end
                                     AND (linked.deleted_at IS NULL
                                          OR linked.deleted_at > snapshot.data_cutoff))::integer
                                   AS invalid_registration,
                               count(*) FILTER (
                                   WHERE linked.is_cancellation
                                     AND linked.cancellation_date IS NULL
                                     AND NOT linked.has_cancellation_raw)::integer
                                   AS missing_cancellation,
                               count(*) FILTER (
                                   WHERE linked.is_cancellation
                                     AND linked.cancellation_date IS NULL
                                     AND linked.has_cancellation_raw)::integer
                                   AS invalid_cancellation
                        FROM market_insight_snapshot snapshot
                        LEFT JOIN linked
                          ON snapshot.scope_type = 'NATIONWIDE'
                          OR (snapshot.scope_type = 'SIDO' AND snapshot.region_code = linked.sido_code)
                        WHERE snapshot.source_execution_id = :executionId
                          AND snapshot.period_type = 'ROLLING_7D'
                          AND snapshot.build_status = 'BUILDING'
                        GROUP BY snapshot.snapshot_id
                    )
                    UPDATE market_insight_snapshot snapshot
                    SET missing_registration_date_count = quality.missing_registration,
                        invalid_registration_date_count = quality.invalid_registration,
                        missing_cancellation_date_count = quality.missing_cancellation,
                        invalid_cancellation_date_count = quality.invalid_cancellation
                    FROM quality
                    WHERE snapshot.snapshot_id = quality.snapshot_id
                    """).param("executionId", executionId).update();
    }

    private void insertNewAndHighest(UUID executionId) {
        jdbcClient
                .sql("""
                    WITH source_identity AS (
                        SELECT raw.source, raw.source_key,
                               max(raw.registration_date) AS registration_date,
                               max(raw.cancellation_date) AS cancellation_date,
                               min(raw.processed_at) AS disclosed_at,
                               bool_or(raw.status = 'CANCELED') AS is_cancellation
                        FROM raw_trade_ingest raw
                        WHERE raw.execution_correlation_id = :executionId
                        GROUP BY raw.source, raw.source_key
                    ), linked AS (
                        SELECT identity.*, trade.id AS trade_id, trade.deal_date AS trade_deal_date,
                               trade.complex_id, trade.deal_amount, trade.excl_area, trade.deleted_at,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'si-do' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-do' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-do' THEN region2.code END
                               ) AS sido_code
                        FROM source_identity identity
                        JOIN trade_source_key_registry registry
                          ON registry.source = identity.source
                         AND registry.source_key = identity.source_key
                        JOIN trade
                          ON trade.id = registry.trade_id
                         AND trade.deal_date = registry.trade_deal_date
                        JOIN complex ON complex.id = trade.complex_id
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                    ), candidate AS (
                        SELECT snapshot.snapshot_id, snapshot.data_cutoff, linked.*
                        FROM market_insight_snapshot snapshot
                        JOIN linked
                          ON snapshot.scope_type = 'NATIONWIDE'
                          OR (snapshot.scope_type = 'SIDO' AND snapshot.region_code = linked.sido_code)
                        WHERE snapshot.source_execution_id = :executionId
                          AND snapshot.period_type = 'ROLLING_7D'
                          AND snapshot.build_status = 'BUILDING'
                          AND NOT linked.is_cancellation
                          AND (linked.deleted_at IS NULL OR linked.deleted_at > snapshot.data_cutoff)
                          AND linked.trade_deal_date
                              BETWEEN snapshot.period_end
                                      - (:currentDealLookbackMonths * INTERVAL '1 month')
                                  AND snapshot.period_end
                          AND (
                              linked.registration_date BETWEEN snapshot.period_start AND snapshot.period_end
                              OR (
                                  linked.registration_date IS NULL
                                  AND linked.trade_deal_date
                                      BETWEEN snapshot.period_start AND snapshot.period_end
                              )
                          )
                    ), metric_rows AS (
                        SELECT snapshot_id, 'ROLLING_7D_NEW_TRADE'::varchar AS metric_type,
                               row_number() OVER (
                                   PARTITION BY snapshot_id
                                   ORDER BY COALESCE(registration_date, trade_deal_date) DESC,
                                            trade_id ASC
                               ) AS item_rank,
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                               registration_date, cancellation_date, disclosed_at, deleted_at, data_cutoff
                        FROM candidate
                        UNION ALL
                        SELECT snapshot_id, 'ROLLING_7D_HIGHEST_DEAL'::varchar,
                               row_number() OVER (
                                   PARTITION BY snapshot_id
                                   ORDER BY deal_amount DESC,
                                            COALESCE(registration_date, trade_deal_date) DESC,
                                            trade_deal_date DESC, trade_id ASC
                               ),
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                               registration_date, cancellation_date, disclosed_at, deleted_at, data_cutoff
                        FROM candidate
                    )
                    INSERT INTO market_insight_trade_item (
                        snapshot_id, metric_type, rank, trade_id, trade_deal_date, complex_id,
                        deal_amount, excl_area, deal_date, disclosed_at, registration_date,
                        cancellation_date, captured_trade_status, canceled_at
                    )
                    SELECT snapshot_id, metric_type, item_rank, trade_id, trade_deal_date, complex_id,
                           deal_amount, excl_area, trade_deal_date, disclosed_at, registration_date,
                           cancellation_date,
                           CASE WHEN deleted_at IS NULL OR deleted_at > data_cutoff
                                THEN 'ACTIVE' ELSE 'CANCELED' END,
                           deleted_at
                    FROM metric_rows
                    WHERE item_rank <= 50
                    """)
                .param("executionId", executionId)
                .param("currentDealLookbackMonths", RANKING_POLICY.currentDealLookbackMonths())
                .update();
    }

    private void insertExactAreaComparisons(UUID executionId) {
        jdbcClient
                .sql("""
                    WITH source_identity AS (
                        SELECT raw.source, raw.source_key,
                               max(raw.registration_date) AS registration_date,
                               min(raw.processed_at) AS disclosed_at,
                               bool_or(raw.status = 'CANCELED') AS is_cancellation
                        FROM raw_trade_ingest raw
                        WHERE raw.execution_correlation_id = :executionId
                        GROUP BY raw.source, raw.source_key
                    ), linked AS (
                        SELECT identity.*, trade.id AS trade_id, trade.deal_date AS trade_deal_date,
                               trade.complex_id, trade.deal_amount, trade.excl_area, trade.deleted_at,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'si-do' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-do' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-do' THEN region2.code END
                               ) AS sido_code
                        FROM source_identity identity
                        JOIN trade_source_key_registry registry
                          ON registry.source = identity.source
                         AND registry.source_key = identity.source_key
                        JOIN trade
                          ON trade.id = registry.trade_id
                         AND trade.deal_date = registry.trade_deal_date
                        JOIN complex ON complex.id = trade.complex_id
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                    ), candidates AS (
                        SELECT snapshot.snapshot_id, snapshot.data_cutoff, linked.*
                        FROM market_insight_snapshot snapshot
                        JOIN linked
                          ON snapshot.scope_type = 'NATIONWIDE'
                          OR (snapshot.scope_type = 'SIDO' AND snapshot.region_code = linked.sido_code)
                        WHERE snapshot.source_execution_id = :executionId
                          AND snapshot.period_type = 'ROLLING_7D'
                          AND snapshot.build_status = 'BUILDING'
                          AND COALESCE(linked.registration_date, linked.trade_deal_date)
                              BETWEEN snapshot.period_start AND snapshot.period_end
                          AND linked.trade_deal_date
                              BETWEEN snapshot.period_end
                                      - (:currentDealLookbackMonths * INTERVAL '1 month')
                                  AND snapshot.period_end
                          AND NOT linked.is_cancellation
                          AND (linked.deleted_at IS NULL OR linked.deleted_at > snapshot.data_cutoff)
                    ), calculated AS (
                        SELECT candidate.*,
                               record_previous.id AS record_previous_id,
                               record_previous.deal_amount AS record_previous_amount,
                               record_previous.deal_date AS record_previous_date,
                               previous_date.comparison_trade_id,
                               previous_date.previous_amount,
                               previous_date.previous_deal_date,
                               previous_date.sample_count,
                               candidate.deal_amount - previous_date.previous_amount AS previous_delta_amount,
                               round((candidate.deal_amount - previous_date.previous_amount)::numeric
                                   * 100 / previous_date.previous_amount, 6) AS previous_delta_rate
                        FROM candidates candidate
                        JOIN LATERAL (
                            SELECT history.id, history.deal_amount, history.deal_date
                            FROM trade history
                            WHERE history.complex_id = candidate.complex_id
                              AND history.excl_area = candidate.excl_area
                              AND history.deal_date < candidate.trade_deal_date
                              AND (history.deleted_at IS NULL OR history.deleted_at > candidate.data_cutoff)
                            ORDER BY history.deal_amount DESC, history.deal_date DESC, history.id ASC
                            LIMIT 1
                        ) record_previous ON TRUE
                        JOIN LATERAL (
                            SELECT percentile_disc(0.5) WITHIN GROUP (ORDER BY history.deal_amount)
                                       AS previous_amount,
                                   (array_agg(history.id ORDER BY history.deal_amount, history.id))
                                       [((count(*) + 1) / 2)::integer] AS comparison_trade_id,
                                   history.deal_date AS previous_deal_date,
                                   count(*)::integer AS sample_count
                            FROM trade history
                            WHERE history.complex_id = candidate.complex_id
                              AND history.excl_area = candidate.excl_area
                              AND history.deal_date = (
                                  SELECT max(previous.deal_date)
                                  FROM trade previous
                                  WHERE previous.complex_id = candidate.complex_id
                                    AND previous.excl_area = candidate.excl_area
                                    AND previous.deal_date < candidate.trade_deal_date
                                    AND previous.deal_date >= candidate.trade_deal_date
                                        - (:previousDealLookbackMonths * INTERVAL '1 month')
                                    AND (previous.deleted_at IS NULL
                                         OR previous.deleted_at > candidate.data_cutoff)
                              )
                              AND (history.deleted_at IS NULL OR history.deleted_at > candidate.data_cutoff)
                            GROUP BY history.deal_date
                        ) previous_date ON TRUE
                    ), metric_rows AS (
                        SELECT snapshot_id, 'AREA_RECORD_HIGH'::varchar AS metric_type,
                               row_number() OVER (
                                   PARTITION BY snapshot_id
                                   ORDER BY deal_amount - record_previous_amount DESC,
                                            round((deal_amount - record_previous_amount)::numeric * 100
                                                / record_previous_amount, 6) DESC,
                                            COALESCE(registration_date, trade_deal_date) DESC,
                                            trade_id ASC
                               ) AS item_rank,
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                               disclosed_at, registration_date,
                               record_previous_id AS comparison_trade_id,
                               record_previous_amount AS previous_amount,
                               record_previous_date AS previous_deal_date,
                               deal_amount - record_previous_amount AS delta_amount,
                               round((deal_amount - record_previous_amount)::numeric * 100
                                   / record_previous_amount, 6) AS delta_rate,
                               sample_count
                        FROM calculated
                        WHERE deal_amount > record_previous_amount
                        UNION ALL
                        SELECT snapshot_id, 'AREA_PREVIOUS_RISE'::varchar,
                               row_number() OVER (
                                   PARTITION BY snapshot_id
                                   ORDER BY previous_delta_rate DESC,
                                            COALESCE(registration_date, trade_deal_date) DESC,
                                            trade_id ASC
                               ),
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                               disclosed_at, registration_date, comparison_trade_id,
                               previous_amount, previous_deal_date,
                               previous_delta_amount, previous_delta_rate, sample_count
                        FROM calculated
                        WHERE previous_delta_amount > 0
                        UNION ALL
                        SELECT snapshot_id, 'AREA_PREVIOUS_FALL'::varchar,
                               row_number() OVER (
                                   PARTITION BY snapshot_id
                                   ORDER BY previous_delta_rate ASC,
                                            COALESCE(registration_date, trade_deal_date) DESC,
                                            trade_id ASC
                               ),
                               trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                               disclosed_at, registration_date, comparison_trade_id,
                               previous_amount, previous_deal_date,
                               previous_delta_amount, previous_delta_rate, sample_count
                        FROM calculated
                        WHERE previous_delta_amount < 0
                    )
                    INSERT INTO market_insight_trade_item (
                        snapshot_id, metric_type, rank, trade_id, trade_deal_date, complex_id,
                        deal_amount, excl_area, deal_date, disclosed_at, registration_date,
                        previous_amount, previous_deal_date, delta_amount, delta_rate,
                        comparison_sample_count, comparison_trade_id,
                        comparison_trade_deal_date, captured_trade_status
                    )
                    SELECT snapshot_id, metric_type, item_rank, trade_id, trade_deal_date, complex_id,
                           deal_amount, excl_area, trade_deal_date, disclosed_at, registration_date,
                           previous_amount, previous_deal_date, delta_amount, delta_rate,
                           sample_count, comparison_trade_id, previous_deal_date, 'ACTIVE'
                    FROM metric_rows
                    WHERE item_rank <= 50
                    """)
                .param("executionId", executionId)
                .param("currentDealLookbackMonths", RANKING_POLICY.currentDealLookbackMonths())
                .param("previousDealLookbackMonths", RANKING_POLICY.previousDealLookbackMonths())
                .update();
    }

    private void insertCancellations(UUID executionId) {
        jdbcClient.sql("""
                    WITH source_identity AS (
                        SELECT raw.source, raw.source_key,
                               max(raw.registration_date) AS registration_date,
                               max(raw.cancellation_date) AS cancellation_date,
                               min(raw.processed_at) AS disclosed_at,
                               bool_or(raw.status = 'CANCELED') AS is_cancellation
                        FROM raw_trade_ingest raw
                        WHERE raw.execution_correlation_id = :executionId
                        GROUP BY raw.source, raw.source_key
                    ), linked AS (
                        SELECT identity.*, trade.id AS trade_id, trade.deal_date AS trade_deal_date,
                               trade.complex_id, trade.deal_amount, trade.excl_area, trade.deleted_at,
                               COALESCE(
                                   CASE WHEN region0.region_type = 'si-do' THEN region0.code END,
                                   CASE WHEN region1.region_type = 'si-do' THEN region1.code END,
                                   CASE WHEN region2.region_type = 'si-do' THEN region2.code END
                               ) AS sido_code
                        FROM source_identity identity
                        JOIN trade_source_key_registry registry
                          ON registry.source = identity.source
                         AND registry.source_key = identity.source_key
                        JOIN trade
                          ON trade.id = registry.trade_id
                         AND trade.deal_date = registry.trade_deal_date
                        JOIN complex ON complex.id = trade.complex_id
                        LEFT JOIN region region0 ON region0.id = complex.region_id
                        LEFT JOIN region region1 ON region1.id = region0.parent_id
                        LEFT JOIN region region2 ON region2.id = region1.parent_id
                        WHERE identity.is_cancellation
                    ), candidate AS (
                        SELECT snapshot.snapshot_id, linked.*
                        FROM market_insight_snapshot snapshot
                        JOIN linked
                          ON snapshot.scope_type = 'NATIONWIDE'
                          OR (snapshot.scope_type = 'SIDO' AND snapshot.region_code = linked.sido_code)
                        WHERE snapshot.source_execution_id = :executionId
                          AND snapshot.period_type = 'ROLLING_7D'
                          AND snapshot.build_status = 'BUILDING'
                          AND linked.cancellation_date BETWEEN snapshot.period_start AND snapshot.period_end
                    ), ranked AS (
                        SELECT candidate.*,
                               row_number() OVER (
                                   PARTITION BY snapshot_id
                                   ORDER BY cancellation_date DESC, trade_id ASC
                               ) AS item_rank
                        FROM candidate
                    )
                    INSERT INTO market_insight_trade_item (
                        snapshot_id, metric_type, rank, trade_id, trade_deal_date, complex_id,
                        deal_amount, excl_area, deal_date, disclosed_at, registration_date,
                        cancellation_date, captured_trade_status, canceled_at
                    )
                    SELECT snapshot_id, 'CANCELLATION_CORRECTION', item_rank,
                           trade_id, trade_deal_date, complex_id, deal_amount, excl_area,
                           trade_deal_date, disclosed_at, registration_date,
                           cancellation_date, 'CANCELED', deleted_at
                    FROM ranked
                    WHERE item_rank <= 50
                    """).param("executionId", executionId).update();
    }
}
