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
			SELECT
			    r.id,
			    r.name,
			    r.center_lat AS lat,
			    r.center_lng AS lng,
			    NULL::double precision AS trend
			FROM region r
			WHERE r.region_type = :region
			  AND r.center_lat IS NOT NULL
			  AND r.center_lng IS NOT NULL
			  AND r.center_lat BETWEEN :swLat AND :neLat
			  AND r.center_lng BETWEEN :swLng AND :neLng
			ORDER BY r.id
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
			doubleOrNull(resultSet, "trend")
		);
	}

	private Double doubleOrNull(ResultSet resultSet, String columnName) throws SQLException {
		double value = resultSet.getDouble(columnName);
		return resultSet.wasNull() ? null : value;
	}
}
