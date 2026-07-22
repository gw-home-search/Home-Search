package com.home.infrastructure.persistence.insight;

import com.home.application.insight.read.MarketInsightReadRepository;
import com.home.application.insight.read.MarketInsightSnapshotView;
import com.home.application.insight.read.MarketInsightTradeItemView;
import com.home.domain.insight.MarketInsightMetricType;
import com.home.domain.insight.MarketInsightScopeType;
import com.home.domain.insight.MarketInsightTradeStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMarketInsightReadRepository implements MarketInsightReadRepository {

    private final JdbcClient jdbcClient;

    public JdbcMarketInsightReadRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public Optional<MarketInsightSnapshotView> findLatestDaily(
            MarketInsightScopeType scopeType, String regionCode, LocalDate onOrBefore, int limit) {
        Optional<SnapshotRow> snapshot = jdbcClient
                .sql("""
                    SELECT snapshot_id, period_start, period_end, generated_at, data_cutoff,
                           scope_type, region_code
                    FROM market_insight_snapshot
                    WHERE period_type = 'DAILY'
                      AND build_status = 'PUBLISHED'
                      AND scope_type = :scopeType
                      AND ((CAST(:regionCode AS varchar) IS NULL AND region_code IS NULL)
                           OR region_code = :regionCode)
                      AND period_start <= :onOrBefore
                    ORDER BY period_start DESC, generated_at DESC
                    LIMIT 1
                    """)
                .param("scopeType", scopeType.name())
                .param("regionCode", regionCode)
                .param("onOrBefore", onOrBefore)
                .query((rs, rowNum) -> new SnapshotRow(
                        rs.getObject("snapshot_id", UUID.class),
                        rs.getObject("period_start", LocalDate.class),
                        rs.getObject("period_end", LocalDate.class),
                        instant(rs, "generated_at"),
                        instant(rs, "data_cutoff"),
                        MarketInsightScopeType.valueOf(rs.getString("scope_type")),
                        rs.getString("region_code")))
                .optional();
        return snapshot.map(row -> row.toView(findItems(row.snapshotId(), limit)));
    }

    private List<MarketInsightTradeItemView> findItems(UUID snapshotId, int limit) {
        return jdbcClient
                .sql("""
                    SELECT item.metric_type, item.rank, item.complex_id, complex.parcel_id,
                           COALESCE(NULLIF(BTRIM(complex.trade_name), ''), complex.name) AS complex_name,
                           COALESCE(
                               CASE WHEN region0.region_type = 'si-do' THEN region0.name END,
                               CASE WHEN region1.region_type = 'si-do' THEN region1.name END,
                               CASE WHEN region2.region_type = 'si-do' THEN region2.name END
                           ) AS sido_name,
                           COALESCE(
                               CASE WHEN region0.region_type = 'si-gun-gu' THEN region0.name END,
                               CASE WHEN region1.region_type = 'si-gun-gu' THEN region1.name END,
                               CASE WHEN region2.region_type = 'si-gun-gu' THEN region2.name END
                           ) AS sigungu_name,
                           item.excl_area, item.deal_amount, item.deal_date, item.disclosed_at,
                           item.previous_amount, item.previous_deal_date, item.delta_amount, item.delta_rate,
                           item.current_count, item.previous_count, item.comparison_sample_count,
                           CASE WHEN trade.deleted_at IS NOT NULL OR item.captured_trade_status = 'CANCELED'
                                THEN 'CANCELED' ELSE 'ACTIVE' END AS trade_status
                    FROM market_insight_trade_item item
                    JOIN complex ON complex.id = item.complex_id
                    LEFT JOIN region region0 ON region0.id = complex.region_id
                    LEFT JOIN region region1 ON region1.id = region0.parent_id
                    LEFT JOIN region region2 ON region2.id = region1.parent_id
                    LEFT JOIN trade
                      ON trade.id = item.trade_id AND trade.deal_date = item.trade_deal_date
                    WHERE item.snapshot_id = :snapshotId
                      AND item.rank <= :limit
                    ORDER BY item.metric_type, item.rank
                    """)
                .param("snapshotId", snapshotId)
                .param("limit", limit)
                .query(this::mapItem)
                .list();
    }

    private MarketInsightTradeItemView mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new MarketInsightTradeItemView(
                MarketInsightMetricType.valueOf(rs.getString("metric_type")),
                rs.getInt("rank"),
                rs.getLong("complex_id"),
                rs.getLong("parcel_id"),
                rs.getString("complex_name"),
                rs.getString("sido_name"),
                rs.getString("sigungu_name"),
                rs.getBigDecimal("excl_area"),
                longOrNull(rs, "deal_amount"),
                rs.getObject("deal_date", LocalDate.class),
                instant(rs, "disclosed_at"),
                longOrNull(rs, "previous_amount"),
                rs.getObject("previous_deal_date", LocalDate.class),
                longOrNull(rs, "delta_amount"),
                rs.getBigDecimal("delta_rate"),
                integerOrNull(rs, "current_count"),
                integerOrNull(rs, "previous_count"),
                integerOrNull(rs, "comparison_sample_count"),
                MarketInsightTradeStatus.valueOf(rs.getString("trade_status")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private record SnapshotRow(
            UUID snapshotId,
            LocalDate periodStart,
            LocalDate periodEnd,
            Instant generatedAt,
            Instant dataCutoff,
            MarketInsightScopeType scopeType,
            String regionCode) {

        MarketInsightSnapshotView toView(List<MarketInsightTradeItemView> items) {
            return new MarketInsightSnapshotView(
                    snapshotId, periodStart, periodEnd, generatedAt, dataCutoff, scopeType, regionCode, items);
        }
    }
}
