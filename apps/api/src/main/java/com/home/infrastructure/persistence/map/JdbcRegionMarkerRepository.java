package com.home.infrastructure.persistence.map;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import com.home.application.map.RegionMarkerQuery;
import com.home.application.map.RegionMarkerRepository;
import com.home.application.map.RegionMarkerResult;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcRegionMarkerRepository implements RegionMarkerRepository {

	private final JdbcClient jdbcClient;

	public JdbcRegionMarkerRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<RegionMarkerResult> findRegionMarkers(RegionMarkerQuery query) {
		return jdbcClient.sql("""
			WITH RECURSIVE marker_region AS (
			    SELECT
			        r.id,
			        r.name,
			        r.center_lat AS lat,
			        r.center_lng AS lng
			    FROM region r
			    WHERE r.region_type = :region
			      AND r.center_lat IS NOT NULL
			      AND r.center_lng IS NOT NULL
			      AND r.center_lat BETWEEN :swLat AND :neLat
			      AND r.center_lng BETWEEN :swLng AND :neLng
			),
			region_descendant AS (
			    SELECT
			        marker_region.id AS marker_id,
			        marker_region.id AS region_id
			    FROM marker_region
			    UNION ALL
			    SELECT
			        region_descendant.marker_id,
			        child.id AS region_id
			    FROM region child
			    JOIN region_descendant ON child.parent_id = region_descendant.region_id
			),
			region_unit AS (
			    SELECT
			        region_descendant.marker_id,
			        SUM(c.unit_cnt)::bigint AS unit_cnt_sum
			    FROM region_descendant
			    JOIN parcel p ON p.region_id = region_descendant.region_id
			    JOIN complex c ON c.parcel_id = p.id
			    GROUP BY region_descendant.marker_id
			)
			SELECT
			    marker_region.id,
			    marker_region.name,
			    marker_region.lat,
			    marker_region.lng,
			    NULL::double precision AS trend,
			    region_unit.unit_cnt_sum
			FROM marker_region
			LEFT JOIN region_unit ON region_unit.marker_id = marker_region.id
			ORDER BY marker_region.id
			""")
			.param("region", query.region())
			.param("swLat", query.swLat())
			.param("swLng", query.swLng())
			.param("neLat", query.neLat())
			.param("neLng", query.neLng())
			.query(this::mapMarker)
			.list();
	}

	private RegionMarkerResult mapMarker(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RegionMarkerResult(
			resultSet.getLong("id"),
			resultSet.getString("name"),
			resultSet.getDouble("lat"),
			resultSet.getDouble("lng"),
			doubleOrNull(resultSet, "trend"),
			longOrNull(resultSet, "unit_cnt_sum")
		);
	}

	private Double doubleOrNull(ResultSet resultSet, String columnName) throws SQLException {
		double value = resultSet.getDouble(columnName);
		return resultSet.wasNull() ? null : value;
	}

	private Long longOrNull(ResultSet resultSet, String columnName) throws SQLException {
		long value = resultSet.getLong(columnName);
		return resultSet.wasNull() ? null : value;
	}
}
