package com.home.infrastructure.persistence.map;

import com.home.application.map.RegionMarkerQuery;
import com.home.application.map.RegionMarkerRepository;
import com.home.application.map.RegionMarkerResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRegionMarkerRepository implements RegionMarkerRepository {

    private final JdbcClient jdbcClient;

    public JdbcRegionMarkerRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public List<RegionMarkerResult> findRegionMarkers(RegionMarkerQuery query) {
        return jdbcClient
                .sql("""
			SELECT
			    marker.region_id AS id,
			    marker.region_name AS name,
			    marker.lat,
			    marker.lng,
			    NULL::double precision AS trend,
			    marker.unit_cnt_sum
			FROM map_marker_active_generation active
			JOIN map_region_marker_projection marker
			  ON marker.generation_id = active.generation_id
			WHERE active.singleton_id = 1
			  AND marker.region_type = :region
			  AND marker.lat BETWEEN :swLat AND :neLat
			  AND marker.lng BETWEEN :swLng AND :neLng
			ORDER BY marker.region_id
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
                longOrNull(resultSet, "unit_cnt_sum"));
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
