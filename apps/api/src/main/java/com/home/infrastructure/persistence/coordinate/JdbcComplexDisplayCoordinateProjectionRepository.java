package com.home.infrastructure.persistence.coordinate;

import java.util.List;
import java.util.Objects;

import com.home.domain.coordinate.ComplexCoordinateCaseStatus;
import com.home.application.coordinate.display.ComplexDisplayCoordinateCommand;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionRepository;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionTarget;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcComplexDisplayCoordinateProjectionRepository implements ComplexDisplayCoordinateProjectionRepository {

	private final JdbcClient jdbcClient;

	public JdbcComplexDisplayCoordinateProjectionRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<ComplexDisplayCoordinateProjectionTarget> findProjectionTargets(int limit) {
		if (limit < 1) {
			return List.of();
		}
		return jdbcClient.sql("""
			WITH parcel_complex_counts AS (
			    SELECT parcel_id, count(*)::integer AS parcel_complex_count
			    FROM complex
			    GROUP BY parcel_id
			)
			SELECT
			    c.id AS complex_id,
			    p.id AS parcel_id,
			    p.latitude AS parcel_latitude,
			    p.longitude AS parcel_longitude,
			    parcel_complex_counts.parcel_complex_count,
			    coordinate_case.status AS coordinate_case_status,
			    display_coordinate.coordinate_source AS existing_coordinate_source,
			    resolved_link.building_footprint_id AS resolved_building_footprint_id,
			    resolved_link.centroid_lat AS resolved_latitude,
			    resolved_link.centroid_lng AS resolved_longitude,
			    resolved_link.confidence AS resolved_confidence,
			    resolved_link.reason AS resolved_reason
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			JOIN parcel_complex_counts ON parcel_complex_counts.parcel_id = p.id
			LEFT JOIN complex_display_coordinate display_coordinate ON display_coordinate.complex_id = c.id
			LEFT JOIN complex_coordinate_case coordinate_case ON coordinate_case.parcel_id = p.id
			LEFT JOIN LATERAL (
			    SELECT
			        link.building_footprint_id,
			        footprint.centroid_lat,
			        footprint.centroid_lng,
			        link.confidence,
			        link.reason
			    FROM complex_building_link link
			    JOIN building_footprint_snapshot footprint ON footprint.id = link.building_footprint_id
			    WHERE link.complex_id = c.id
			      AND link.status = 'RESOLVED'
			      AND link.building_footprint_id IS NOT NULL
			    ORDER BY link.confidence DESC, link.id DESC
			    LIMIT 1
			) resolved_link ON true
			WHERE display_coordinate.complex_id IS NULL
			   OR (
			       display_coordinate.coordinate_source <> 'BUILDING_FOOTPRINT'
			       AND resolved_link.building_footprint_id IS NOT NULL
			   )
			ORDER BY c.id
			LIMIT :limit
			""")
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new ComplexDisplayCoordinateProjectionTarget(
				resultSet.getLong("complex_id"),
				resultSet.getLong("parcel_id"),
				resultSet.getBigDecimal("parcel_latitude"),
				resultSet.getBigDecimal("parcel_longitude"),
				resultSet.getInt("parcel_complex_count"),
				mapCoordinateCaseStatus(resultSet.getString("coordinate_case_status")),
				resultSet.getString("existing_coordinate_source"),
				nullableLong(resultSet, "resolved_building_footprint_id"),
				resultSet.getBigDecimal("resolved_latitude"),
				resultSet.getBigDecimal("resolved_longitude"),
				nullableInteger(resultSet, "resolved_confidence"),
				resultSet.getString("resolved_reason")
			))
			.list();
	}

	@Override
	public void saveDisplayCoordinate(ComplexDisplayCoordinateCommand command) {
		Objects.requireNonNull(command, "command is required");
		jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    building_footprint_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason,
			    checked_at,
			    updated_at
			)
			VALUES (
			    :complexId,
			    :buildingFootprintId,
			    :latitude,
			    :longitude,
			    :coordinateSource,
			    :confidence,
			    :reason,
			    now(),
			    now()
			)
			ON CONFLICT (complex_id) DO UPDATE SET
			    building_footprint_id = EXCLUDED.building_footprint_id,
			    latitude = EXCLUDED.latitude,
			    longitude = EXCLUDED.longitude,
			    coordinate_source = EXCLUDED.coordinate_source,
			    confidence = EXCLUDED.confidence,
			    reason = EXCLUDED.reason,
			    checked_at = now(),
			    updated_at = now()
			WHERE complex_display_coordinate.coordinate_source <> 'BUILDING_FOOTPRINT'
			   OR EXCLUDED.coordinate_source = 'BUILDING_FOOTPRINT'
			""")
			.param("complexId", command.complexId())
			.param("buildingFootprintId", command.buildingFootprintId())
			.param("latitude", command.latitude())
			.param("longitude", command.longitude())
			.param("coordinateSource", command.coordinateSource())
			.param("confidence", command.confidence())
			.param("reason", command.reason())
			.update();
	}

	private ComplexCoordinateCaseStatus mapCoordinateCaseStatus(String value) {
		if (value == null) {
			return null;
		}
		return ComplexCoordinateCaseStatus.valueOf(value);
	}

	private Long nullableLong(java.sql.ResultSet resultSet, String columnName) throws java.sql.SQLException {
		long value = resultSet.getLong(columnName);
		if (resultSet.wasNull()) {
			return null;
		}
		return value;
	}

	private Integer nullableInteger(java.sql.ResultSet resultSet, String columnName) throws java.sql.SQLException {
		int value = resultSet.getInt(columnName);
		if (resultSet.wasNull()) {
			return null;
		}
		return value;
	}
}
