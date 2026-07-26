package com.home.infrastructure.persistence.news;

import com.home.application.news.collection.PublishedNewsSnapshot;
import com.home.application.news.quality.MarketNewsQualityRepository;
import com.home.application.news.quality.WithdrawnNewsSnapshot;
import com.home.domain.news.MarketNewsScopeType;
import com.home.domain.news.MarketNewsWithdrawalReason;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcMarketNewsQualityRepository implements MarketNewsQualityRepository {

    private final JdbcClient jdbcClient;

    public JdbcMarketNewsQualityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional
    public Optional<WithdrawnNewsSnapshot> withdrawPublished(UUID snapshotId, MarketNewsWithdrawalReason reason) {
        Optional<SnapshotScopeRow> updated = jdbcClient
                .sql("""
                    UPDATE market_news_snapshot
                    SET build_status = 'WITHDRAWN', withdrawn_reason = :reason
                    WHERE snapshot_id = :snapshotId
                      AND build_status = 'PUBLISHED'
                    RETURNING snapshot_id, scope_type, region_code
                    """)
                .param("snapshotId", snapshotId)
                .param("reason", reason.name())
                .query(this::mapScope)
                .optional();
        if (updated.isPresent()) {
            return Optional.of(withLastGood(updated.get()));
        }
        return jdbcClient
                .sql("""
                    SELECT snapshot_id, scope_type, region_code
                    FROM market_news_snapshot
                    WHERE snapshot_id = :snapshotId
                      AND build_status = 'WITHDRAWN'
                      AND withdrawn_reason = :reason
                    """)
                .param("snapshotId", snapshotId)
                .param("reason", reason.name())
                .query(this::mapScope)
                .optional()
                .map(this::withLastGood);
    }

    private SnapshotScopeRow mapScope(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SnapshotScopeRow(
                rs.getObject("snapshot_id", UUID.class),
                MarketNewsScopeType.valueOf(rs.getString("scope_type")),
                rs.getString("region_code"));
    }

    private WithdrawnNewsSnapshot withLastGood(SnapshotScopeRow withdrawn) {
        PublishedNewsSnapshot lastGood = jdbcClient
                .sql("""
                    SELECT snapshot_id, generated_at, data_cutoff
                    FROM market_news_snapshot
                    WHERE build_status = 'SUPERSEDED'
                      AND scope_type = :scopeType
                      AND ((CAST(:regionCode AS varchar) IS NULL AND region_code IS NULL)
                           OR region_code = :regionCode)
                    ORDER BY generated_at DESC
                    LIMIT 1
                    """)
                .param("scopeType", withdrawn.scopeType().name())
                .param("regionCode", withdrawn.regionCode())
                .query((rs, rowNum) -> new PublishedNewsSnapshot(
                        rs.getObject("snapshot_id", UUID.class),
                        withdrawn.scopeType(),
                        withdrawn.regionCode(),
                        rs.getObject("generated_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("data_cutoff", OffsetDateTime.class).toInstant()))
                .optional()
                .orElse(null);
        return new WithdrawnNewsSnapshot(
                withdrawn.snapshotId(), withdrawn.scopeType(), withdrawn.regionCode(), lastGood);
    }

    private record SnapshotScopeRow(UUID snapshotId, MarketNewsScopeType scopeType, String regionCode) {}
}
