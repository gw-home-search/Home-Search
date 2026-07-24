package com.home.infrastructure.persistence.regionnavigation;

import com.home.application.read.ComplexSummaryResult;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import com.home.application.regionnavigation.RegionNavigationReader;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegionNavigationReader implements RegionNavigationReader {

    private final JdbcClient jdbcClient;

    public JdbcRegionNavigationReader(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public List<RegionSummaryResult> findRootRegions() {
        return jdbcClient.sql("""
			SELECT id, name, code
			FROM region
			WHERE parent_id IS NULL
			ORDER BY id
			""").query(this::mapRegionSummary).list();
    }

    @Override
    public Optional<RegionDetailResult> findRegionDetail(Long regionId) {
        Optional<RegionRow> region = jdbcClient
                .sql("""
			SELECT id, name, code, center_lat, center_lng
			FROM region
			WHERE id = :regionId
			""")
                .param("regionId", regionId)
                .query(this::mapRegionRow)
                .optional();
        if (region.isEmpty()) {
            return Optional.empty();
        }
        List<RegionSummaryResult> children = jdbcClient
                .sql("""
			SELECT id, name, code
			FROM region
			WHERE parent_id = :regionId
			ORDER BY id
			""")
                .param("regionId", regionId)
                .query(this::mapRegionSummary)
                .list();
        RegionRow row = region.get();
        return Optional.of(
                new RegionDetailResult(row.id(), row.name(), row.code(), row.latitude(), row.longitude(), children));
    }

    @Override
    public Optional<List<ComplexSummaryResult>> findRegionComplexes(Long regionId, int limit, int offset) {
        if (!hasRegion(regionId)) {
            return Optional.empty();
        }
        List<ComplexSummaryResult> complexes = jdbcClient
                .sql("""
			WITH RECURSIVE region_tree AS (
			    SELECT id
			    FROM region
			    WHERE id = :regionId
			    UNION ALL
			    SELECT child.id
			    FROM region child
			    JOIN region_tree parent ON parent.id = child.parent_id
			)
			SELECT
			    c.id AS complex_id,
			    COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name) AS complex_name,
			    p.id AS parcel_id,
			    COALESCE(display_coordinate.latitude, p.latitude) AS latitude,
			    COALESCE(display_coordinate.longitude, p.longitude) AS longitude,
			    p.address,
			    c.dong_cnt,
			    c.unit_cnt,
			    c.use_date
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			LEFT JOIN complex_display_coordinate display_coordinate ON display_coordinate.complex_id = c.id
			WHERE p.region_id IN (SELECT id FROM region_tree)
			ORDER BY COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name), c.id
			LIMIT :limit OFFSET :offset
			""")
                .param("regionId", regionId)
                .param("limit", limit)
                .param("offset", offset)
                .query(this::mapComplexSummary)
                .list();
        return Optional.of(complexes);
    }

    private boolean hasRegion(Long regionId) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM region
			    WHERE id = :regionId
			)
			""")
                .param("regionId", regionId)
                .query(Boolean.class)
                .single());
    }

    private RegionSummaryResult mapRegionSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RegionSummaryResult(
                resultSet.getLong("id"), resultSet.getString("name"), resultSet.getString("code"));
    }

    private RegionRow mapRegionRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new RegionRow(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("code"),
                doubleOrNull(resultSet, "center_lat"),
                doubleOrNull(resultSet, "center_lng"));
    }

    private ComplexSummaryResult mapComplexSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ComplexSummaryResult(
                resultSet.getLong("complex_id"),
                resultSet.getString("complex_name"),
                resultSet.getLong("parcel_id"),
                doubleOrNull(resultSet, "latitude"),
                doubleOrNull(resultSet, "longitude"),
                resultSet.getString("address"),
                integerOrNull(resultSet, "dong_cnt"),
                integerOrNull(resultSet, "unit_cnt"),
                resultSet.getObject("use_date", LocalDate.class));
    }

    private Integer integerOrNull(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Double doubleOrNull(ResultSet resultSet, String column) throws SQLException {
        BigDecimal value = resultSet.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private record RegionRow(Long id, String name, String code, Double latitude, Double longitude) {}
}
