package com.home.infrastructure.persistence.seo;

import com.home.application.seo.SeoCatalogComplex;
import com.home.application.seo.SeoCatalogRegion;
import com.home.application.seo.SeoComplexResult;
import com.home.application.seo.SeoIndexMode;
import com.home.application.seo.SeoReader;
import com.home.application.seo.SeoRegionResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSeoReader implements SeoReader {
    private static final String ELIGIBLE_COMPLEX_CTES = """
        WITH RECURSIVE redeveloped_parcel AS (
            SELECT parcel_id FROM complex_coordinate_case
            WHERE relation_type = 'REDEVELOPED' AND relation_confidence = 'HIGH'
        ), superseded_complex AS (
            SELECT c.id AS complex_id
            FROM complex c JOIN redeveloped_parcel rp ON rp.parcel_id = c.parcel_id
            WHERE c.id <> (
                SELECT c2.id FROM complex c2
                LEFT JOIN trade t2 ON t2.complex_id = c2.id AND t2.deleted_at IS NULL
                WHERE c2.parcel_id = c.parcel_id
                GROUP BY c2.id
                ORDER BY c2.use_date DESC NULLS LAST, MAX(t2.deal_date) DESC NULLS LAST,
                         MIN(t2.deal_date) DESC NULLS LAST, c2.id DESC
                LIMIT 1
            )
        ), recent_trade_stats AS (
            SELECT complex_id, COUNT(*) AS trade_count_24m, MAX(deal_date) AS latest_trade_date
            FROM trade
            WHERE deleted_at IS NULL
              AND deal_date >= CURRENT_DATE - INTERVAL '24 months'
            GROUP BY complex_id
        ), eligible AS (
            SELECT c.id AS complex_id,
                   COALESCE(NULLIF(BTRIM(c.trade_name), ''), NULLIF(BTRIM(c.name), '')) AS complex_name,
                   p.address,
                   COALESCE(c.region_id, p.region_id) AS region_id,
                   COALESCE(recent_trade.trade_count_24m, 0) AS trade_count_24m,
                   recent_trade.latest_trade_date
            FROM complex c
            JOIN parcel p ON p.id = c.parcel_id
            LEFT JOIN recent_trade_stats recent_trade ON recent_trade.complex_id = c.id
            LEFT JOIN superseded_complex sc ON sc.complex_id = c.id
            WHERE sc.complex_id IS NULL
              AND COALESCE(NULLIF(BTRIM(c.trade_name), ''), NULLIF(BTRIM(c.name), '')) IS NOT NULL
              AND NULLIF(BTRIM(p.address), '') IS NOT NULL
              AND (c.dong_cnt IS NOT NULL OR c.unit_cnt IS NOT NULL OR c.use_date IS NOT NULL
                   OR EXISTS (SELECT 1 FROM complex_building_register_profile_summary summary
                              JOIN building_register_profile_publication publication USING (publication_id)
                              WHERE summary.complex_id=c.id AND publication.status='PUBLISHED')
                   OR EXISTS (SELECT 1 FROM trade active_trade
                              WHERE active_trade.complex_id=c.id AND active_trade.deleted_at IS NULL))
        ), ranked AS (
            SELECT eligible.*, ROW_NUMBER() OVER (
                ORDER BY trade_count_24m DESC, latest_trade_date DESC NULLS LAST, complex_id
            ) AS pilot_rank
            FROM eligible
        ), selected AS (
            SELECT * FROM ranked
            WHERE :mode = 'ALL' OR (:mode = 'PILOT' AND pilot_rank <= 1000)
        )
        """;

    private final JdbcClient jdbcClient;

    public JdbcSeoReader(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public Optional<SeoComplexResult> findComplex(long complexId) {
        Optional<ComplexRow> row = jdbcClient
                .sql("""
                WITH redeveloped_parcel AS (
                    SELECT parcel_id FROM complex_coordinate_case
                    WHERE relation_type='REDEVELOPED' AND relation_confidence='HIGH'
                ), superseded_complex AS (
                    SELECT c.id AS complex_id FROM complex c
                    JOIN redeveloped_parcel rp ON rp.parcel_id=c.parcel_id
                    WHERE c.id <> (
                        SELECT c2.id FROM complex c2
                        LEFT JOIN trade t2 ON t2.complex_id=c2.id AND t2.deleted_at IS NULL
                        WHERE c2.parcel_id=c.parcel_id GROUP BY c2.id
                        ORDER BY c2.use_date DESC NULLS LAST, MAX(t2.deal_date) DESC NULLS LAST,
                                 MIN(t2.deal_date) DESC NULLS LAST, c2.id DESC LIMIT 1
                    )
                )
                SELECT c.id, COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name) AS name,
                       p.address, c.dong_cnt, c.unit_cnt, c.use_date,
                       COALESCE(c.region_id, p.region_id) AS region_id,
                       (c.dong_cnt IS NOT NULL OR c.unit_cnt IS NOT NULL OR c.use_date IS NOT NULL
                        OR EXISTS (SELECT 1 FROM complex_building_register_profile_summary summary
                                   JOIN building_register_profile_publication publication USING (publication_id)
                                   WHERE summary.complex_id=c.id AND publication.status='PUBLISHED')) AS has_building_info,
                       (NULLIF(BTRIM(COALESCE(c.trade_name, c.name)), '') IS NOT NULL
                        AND NULLIF(BTRIM(p.address), '') IS NOT NULL
                        AND (EXISTS (SELECT 1 FROM trade t WHERE t.complex_id=c.id AND t.deleted_at IS NULL)
                             OR c.dong_cnt IS NOT NULL OR c.unit_cnt IS NOT NULL OR c.use_date IS NOT NULL
                             OR EXISTS (SELECT 1 FROM complex_building_register_profile_summary summary
                                        JOIN building_register_profile_publication publication USING (publication_id)
                                        WHERE summary.complex_id=c.id AND publication.status='PUBLISHED'))) AS indexable
                FROM complex c JOIN parcel p ON p.id=c.parcel_id
                LEFT JOIN superseded_complex sc ON sc.complex_id=c.id
                WHERE c.id=:complexId AND sc.complex_id IS NULL
                """)
                .param("complexId", complexId)
                .query(this::mapComplexRow)
                .optional();
        if (row.isEmpty()) return Optional.empty();
        ComplexRow value = row.get();
        return Optional.of(new SeoComplexResult(
                value.id(),
                value.name(),
                value.address(),
                value.indexable(),
                value.dongCount(),
                value.unitCount(),
                value.useDate(),
                value.hasBuildingInfo(),
                breadcrumbs(value.regionId()),
                recentTrades(value.id())));
    }

    @Override
    public Optional<SeoRegionResult> findRegion(long regionId, SeoIndexMode mode) {
        Optional<RegionRow> region = jdbcClient
                .sql("SELECT id, name, parent_id FROM region WHERE id=:regionId")
                .param("regionId", regionId)
                .query(this::mapRegionRow)
                .optional();
        if (region.isEmpty()) return Optional.empty();
        if (mode == SeoIndexMode.PILOT) {
            return Optional.of(pilotRegion(region.get()));
        }
        List<SeoRegionResult.RepresentativeComplex> representatives = jdbcClient
                .sql("""
                WITH RECURSIVE region_tree AS (
                    SELECT id FROM region WHERE id=:regionId
                    UNION ALL SELECT child.id FROM region child JOIN region_tree parent ON child.parent_id=parent.id
                ), redeveloped_parcel AS (
                    SELECT parcel_id FROM complex_coordinate_case
                    WHERE relation_type='REDEVELOPED' AND relation_confidence='HIGH'
                ), superseded_complex AS (
                    SELECT c.id AS complex_id FROM complex c
                    JOIN redeveloped_parcel rp ON rp.parcel_id=c.parcel_id
                    WHERE c.id <> (
                        SELECT c2.id FROM complex c2
                        LEFT JOIN trade t2 ON t2.complex_id=c2.id AND t2.deleted_at IS NULL
                        WHERE c2.parcel_id=c.parcel_id GROUP BY c2.id
                        ORDER BY c2.use_date DESC NULLS LAST, MAX(t2.deal_date) DESC NULLS LAST,
                                 MIN(t2.deal_date) DESC NULLS LAST, c2.id DESC LIMIT 1
                    )
                ), recent_trade_stats AS (
                    SELECT complex_id, COUNT(*) AS activity, MAX(deal_date) AS latest_trade
                    FROM trade
                    WHERE deleted_at IS NULL
                      AND deal_date >= CURRENT_DATE - INTERVAL '24 months'
                    GROUP BY complex_id
                ), candidates AS (
                    SELECT c.id, COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name) AS name, p.address,
                           COALESCE(recent_trade.activity, 0) AS activity,
                           recent_trade.latest_trade
                    FROM complex c JOIN parcel p ON p.id=c.parcel_id
                    LEFT JOIN recent_trade_stats recent_trade ON recent_trade.complex_id=c.id
                    LEFT JOIN superseded_complex sc ON sc.complex_id=c.id
                    WHERE COALESCE(c.region_id,p.region_id) IN (SELECT id FROM region_tree)
                      AND sc.complex_id IS NULL
                      AND NULLIF(BTRIM(COALESCE(c.trade_name,c.name)), '') IS NOT NULL
                      AND NULLIF(BTRIM(p.address), '') IS NOT NULL
                      AND (c.dong_cnt IS NOT NULL OR c.unit_cnt IS NOT NULL OR c.use_date IS NOT NULL
                           OR EXISTS (SELECT 1 FROM complex_building_register_profile_summary summary
                                      JOIN building_register_profile_publication publication USING (publication_id)
                                      WHERE summary.complex_id=c.id AND publication.status='PUBLISHED')
                           OR EXISTS (SELECT 1 FROM trade active_trade
                                      WHERE active_trade.complex_id=c.id AND active_trade.deleted_at IS NULL))
                ) SELECT id,name,address FROM candidates
                  ORDER BY activity DESC, latest_trade DESC NULLS LAST, id LIMIT 5
                """)
                .param("regionId", regionId)
                .query((rs, rowNumber) -> new SeoRegionResult.RepresentativeComplex(
                        rs.getLong("id"), rs.getString("name"), rs.getString("address")))
                .list();
        return Optional.of(new SeoRegionResult(
                regionId,
                region.get().name(),
                !representatives.isEmpty(),
                countIndexableComplexes(regionId),
                breadcrumbs(regionId),
                representatives));
    }

    private SeoRegionResult pilotRegion(RegionRow region) {
        List<SeoRegionResult.RepresentativeComplex> representatives = jdbcClient
                .sql("""
                WITH RECURSIVE region_tree AS (
                    SELECT id FROM region WHERE id=:regionId
                    UNION ALL SELECT child.id FROM region child JOIN region_tree parent ON child.parent_id=parent.id
                )
                SELECT c.id, COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name) AS name, p.address
                FROM seo_pilot_complex_catalog catalog
                JOIN complex c ON c.id=catalog.complex_id
                JOIN parcel p ON p.id=c.parcel_id
                WHERE catalog.region_id IN (SELECT id FROM region_tree)
                ORDER BY catalog.pilot_rank
                LIMIT 5
                """)
                .param("regionId", region.id())
                .query((rs, rowNumber) -> new SeoRegionResult.RepresentativeComplex(
                        rs.getLong("id"), rs.getString("name"), rs.getString("address")))
                .list();
        long count = jdbcClient
                .sql("""
                WITH RECURSIVE region_tree AS (
                    SELECT id FROM region WHERE id=:regionId
                    UNION ALL SELECT child.id FROM region child JOIN region_tree parent ON child.parent_id=parent.id
                )
                SELECT COUNT(*) FROM seo_pilot_complex_catalog
                WHERE region_id IN (SELECT id FROM region_tree)
                """)
                .param("regionId", region.id())
                .query(Long.class)
                .single();
        return new SeoRegionResult(
                region.id(),
                region.name(),
                !representatives.isEmpty(),
                count,
                breadcrumbs(region.id()),
                representatives);
    }

    @Override
    public List<SeoCatalogComplex> findComplexCatalog(SeoIndexMode mode, long afterId, int limit) {
        if (mode == SeoIndexMode.OFF) return List.of();
        if (mode == SeoIndexMode.PILOT) {
            return jdbcClient
                    .sql("""
                    SELECT complex_id
                    FROM seo_pilot_complex_catalog
                    WHERE complex_id > :afterId
                    ORDER BY complex_id
                    LIMIT :limit
                    """)
                    .param("afterId", afterId)
                    .param("limit", limit)
                    .query((rs, rowNumber) -> new SeoCatalogComplex(rs.getLong("complex_id")))
                    .list();
        }
        return jdbcClient
                .sql(ELIGIBLE_COMPLEX_CTES + """
                SELECT complex_id FROM selected WHERE complex_id>:afterId ORDER BY complex_id LIMIT :limit
                """)
                .param("mode", mode.name())
                .param("afterId", afterId)
                .param("limit", limit)
                .query((rs, rowNumber) -> new SeoCatalogComplex(rs.getLong("complex_id")))
                .list();
    }

    @Override
    public List<SeoCatalogRegion> findRegionCatalog(SeoIndexMode mode) {
        if (mode == SeoIndexMode.OFF) return List.of();
        if (mode == SeoIndexMode.PILOT) {
            return jdbcClient
                    .sql("""
                    WITH RECURSIVE region_ancestors AS (
                        SELECT region.id, region.parent_id
                        FROM region
                        JOIN seo_pilot_complex_catalog catalog ON catalog.region_id = region.id
                        UNION
                        SELECT parent.id, parent.parent_id
                        FROM region parent
                        JOIN region_ancestors child ON child.parent_id = parent.id
                    )
                    SELECT DISTINCT id
                    FROM region_ancestors
                    ORDER BY id
                    """)
                    .query((rs, rowNumber) -> new SeoCatalogRegion(rs.getLong("id")))
                    .list();
        }
        return jdbcClient
                .sql(ELIGIBLE_COMPLEX_CTES + """
                , region_ancestors AS (
                    SELECT r.id, r.parent_id FROM region r JOIN selected s ON s.region_id=r.id
                    UNION SELECT parent.id, parent.parent_id FROM region parent
                    JOIN region_ancestors child ON child.parent_id=parent.id
                ) SELECT DISTINCT id FROM region_ancestors ORDER BY id
                """)
                .param("mode", mode.name())
                .query((rs, rowNumber) -> new SeoCatalogRegion(rs.getLong("id")))
                .list();
    }

    private List<SeoComplexResult.Breadcrumb> breadcrumbs(Long regionId) {
        if (regionId == null) return List.of();
        return jdbcClient
                .sql("""
                WITH RECURSIVE ancestors AS (
                    SELECT id,parent_id,name,0 AS depth FROM region WHERE id=:regionId
                    UNION ALL SELECT parent.id,parent.parent_id,parent.name,child.depth+1
                    FROM region parent JOIN ancestors child ON child.parent_id=parent.id
                ) SELECT id,name FROM ancestors ORDER BY depth DESC
                """)
                .param("regionId", regionId)
                .query((rs, rowNumber) -> new SeoComplexResult.Breadcrumb(rs.getLong("id"), rs.getString("name")))
                .list();
    }

    private List<SeoComplexResult.RecentTrade> recentTrades(long complexId) {
        return jdbcClient
                .sql("""
                SELECT deal_date,deal_amount,excl_area,floor FROM trade
                WHERE complex_id=:complexId AND deleted_at IS NULL
                ORDER BY deal_date DESC,id DESC LIMIT 5
                """)
                .param("complexId", complexId)
                .query((rs, rowNumber) -> new SeoComplexResult.RecentTrade(
                        rs.getObject("deal_date", LocalDate.class), rs.getLong("deal_amount"),
                        rs.getBigDecimal("excl_area"), integerOrNull(rs, "floor")))
                .list();
    }

    private long countIndexableComplexes(long regionId) {
        return jdbcClient.sql("""
                WITH RECURSIVE region_tree AS (
                    SELECT id FROM region WHERE id=:regionId
                    UNION ALL SELECT child.id FROM region child JOIN region_tree parent ON child.parent_id=parent.id
                ), redeveloped_parcel AS (
                    SELECT parcel_id FROM complex_coordinate_case
                    WHERE relation_type='REDEVELOPED' AND relation_confidence='HIGH'
                ), superseded_complex AS (
                    SELECT c.id AS complex_id FROM complex c
                    JOIN redeveloped_parcel rp ON rp.parcel_id=c.parcel_id
                    WHERE c.id <> (
                        SELECT c2.id FROM complex c2
                        LEFT JOIN trade t2 ON t2.complex_id=c2.id AND t2.deleted_at IS NULL
                        WHERE c2.parcel_id=c.parcel_id GROUP BY c2.id
                        ORDER BY c2.use_date DESC NULLS LAST, MAX(t2.deal_date) DESC NULLS LAST,
                                 MIN(t2.deal_date) DESC NULLS LAST, c2.id DESC LIMIT 1
                    )
                ) SELECT COUNT(*) FROM complex c JOIN parcel p ON p.id=c.parcel_id
                LEFT JOIN superseded_complex sc ON sc.complex_id=c.id
                WHERE COALESCE(c.region_id,p.region_id) IN (SELECT id FROM region_tree)
                  AND sc.complex_id IS NULL
                  AND NULLIF(BTRIM(COALESCE(c.trade_name,c.name)), '') IS NOT NULL
                  AND NULLIF(BTRIM(p.address), '') IS NOT NULL
                  AND (c.dong_cnt IS NOT NULL OR c.unit_cnt IS NOT NULL OR c.use_date IS NOT NULL
                       OR EXISTS (SELECT 1 FROM complex_building_register_profile_summary summary
                                  JOIN building_register_profile_publication publication USING (publication_id)
                                  WHERE summary.complex_id=c.id AND publication.status='PUBLISHED')
                       OR EXISTS (SELECT 1 FROM trade active_trade
                                  WHERE active_trade.complex_id=c.id AND active_trade.deleted_at IS NULL))
                """).param("regionId", regionId).query(Long.class).single();
    }

    private ComplexRow mapComplexRow(ResultSet rs, int rowNumber) throws SQLException {
        return new ComplexRow(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("address"),
                integerOrNull(rs, "dong_cnt"),
                integerOrNull(rs, "unit_cnt"),
                rs.getObject("use_date", LocalDate.class),
                longOrNull(rs, "region_id"),
                rs.getBoolean("has_building_info"),
                rs.getBoolean("indexable"));
    }

    private RegionRow mapRegionRow(ResultSet rs, int rowNumber) throws SQLException {
        return new RegionRow(rs.getLong("id"), rs.getString("name"), longOrNull(rs, "parent_id"));
    }

    private Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record ComplexRow(
            long id,
            String name,
            String address,
            Integer dongCount,
            Integer unitCount,
            LocalDate useDate,
            Long regionId,
            boolean hasBuildingInfo,
            boolean indexable) {}

    private record RegionRow(long id, String name, Long parentId) {}
}
