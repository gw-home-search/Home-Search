package com.home.infrastructure.persistence.coordinate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import com.home.application.coordinate.CoordinateOverrideAdminRepository;
import com.home.application.coordinate.CoordinateOverrideApprovalCommand;
import com.home.application.coordinate.CoordinateOverrideApprovalResult;
import com.home.application.coordinate.CoordinatePendingComplex;
import com.home.application.coordinate.CoordinatePendingReason;

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcCoordinateOverrideAdminRepository implements CoordinateOverrideAdminRepository {

	private final JdbcClient jdbcClient;

	JdbcCoordinateOverrideAdminRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<CoordinatePendingComplex> findPendingComplexes(int limit) {
		return jdbcClient.sql("""
			WITH pending_base AS (
			    SELECT
			        p.id AS parcel_id,
			        c.id AS complex_id,
			        p.pnu,
			        c.apt_seq,
			        COALESCE(c.trade_name, c.name) AS apt_name,
			        p.address,
			        c.created_at,
			        display_coordinate.complex_id IS NOT NULL AS has_display_coordinate,
			        count(*) OVER (PARTITION BY p.id) AS complex_count,
			        count(display_coordinate.complex_id) OVER (PARTITION BY p.id) AS display_coordinate_count,
			        CASE
			            WHEN p.latitude IS NULL OR p.longitude IS NULL THEN 'PNU_COORDINATE_MISSING'
			            WHEN count(*) OVER (PARTITION BY p.id) > 1
			             AND count(display_coordinate.complex_id) OVER (PARTITION BY p.id) = 0 THEN 'SAME_PNU_MULTI_COMPLEX'
			            WHEN count(*) OVER (PARTITION BY p.id) > 1
			             AND display_coordinate.complex_id IS NULL THEN 'COMPLEX_DISPLAY_COORDINATE_MISSING'
			            ELSE NULL
			        END AS reason
			    FROM parcel p
			    JOIN complex c ON c.parcel_id = p.id
			    LEFT JOIN complex_display_coordinate display_coordinate
			      ON display_coordinate.complex_id = c.id
			     AND display_coordinate.coordinate_source = 'BUILDING_FOOTPRINT'
			     AND display_coordinate.confidence >= 80
			)
			SELECT
			    pending_base.parcel_id,
			    pending_base.complex_id,
			    pending_base.pnu,
			    pending_base.apt_seq,
			    pending_base.apt_name,
			    pending_base.address,
			    pending_base.reason,
			    count(t.id) AS trade_count,
			    pending_base.created_at
			FROM pending_base
			LEFT JOIN trade t ON t.complex_id = pending_base.complex_id AND t.deleted_at IS NULL
			WHERE pending_base.reason IS NOT NULL
			GROUP BY
			    pending_base.parcel_id,
			    pending_base.complex_id,
			    pending_base.pnu,
			    pending_base.apt_seq,
			    pending_base.apt_name,
			    pending_base.address,
			    pending_base.reason,
			    pending_base.created_at
			ORDER BY count(t.id) DESC, pending_base.created_at DESC, pending_base.parcel_id, pending_base.complex_id
			LIMIT :limit
			""")
			.param("limit", limit)
			.query(this::mapPendingComplex)
			.list();
	}

	@Override
	public CoordinateOverrideApprovalResult approve(CoordinateOverrideApprovalCommand command) {
		int updatedOverride = updateApprovedOverride(command);
		if (updatedOverride == 0) {
			insertApprovedOverride(command);
		}
		int updatedParcel = jdbcClient.sql("""
			UPDATE parcel
			SET latitude = :latitude,
			    longitude = :longitude,
			    updated_at = now()
			WHERE pnu = :pnu
			""")
			.param("latitude", command.latitude())
			.param("longitude", command.longitude())
			.param("pnu", command.pnu())
			.update();
		return new CoordinateOverrideApprovalResult(
			command.pnu(),
			command.latitude(),
			command.longitude(),
			updatedParcel > 0
		);
	}

	private int updateApprovedOverride(CoordinateOverrideApprovalCommand command) {
		return jdbcClient.sql("""
			UPDATE parcel_coordinate_override
			SET latitude = :latitude,
			    longitude = :longitude,
			    source = 'MANUAL',
			    confidence = 'HIGH',
			    reason = :reason,
			    source_payload = '{}'::jsonb,
			    approved_by = :approvedBy,
			    approved_at = now(),
			    updated_at = now()
			WHERE pnu = :pnu
			  AND status = 'APPROVED'
			""")
			.param("latitude", command.latitude())
			.param("longitude", command.longitude())
			.param("reason", command.reason())
			.param("approvedBy", command.approvedBy())
			.param("pnu", command.pnu())
			.update();
	}

	private void insertApprovedOverride(CoordinateOverrideApprovalCommand command) {
		jdbcClient.sql("""
			INSERT INTO parcel_coordinate_override (
			    pnu,
			    latitude,
			    longitude,
			    source,
			    confidence,
			    status,
			    reason,
			    source_payload,
			    approved_by,
			    approved_at
			)
			VALUES (
			    :pnu,
			    :latitude,
			    :longitude,
			    'MANUAL',
			    'HIGH',
			    'APPROVED',
			    :reason,
			    '{}'::jsonb,
			    :approvedBy,
			    now()
			)
			""")
			.param("pnu", command.pnu())
			.param("latitude", command.latitude())
			.param("longitude", command.longitude())
			.param("reason", command.reason())
			.param("approvedBy", command.approvedBy())
			.update();
	}

	private CoordinatePendingComplex mapPendingComplex(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CoordinatePendingComplex(
			resultSet.getLong("parcel_id"),
			resultSet.getLong("complex_id"),
			resultSet.getString("pnu"),
			resultSet.getString("apt_seq"),
			resultSet.getString("apt_name"),
			resultSet.getString("address"),
			CoordinatePendingReason.valueOf(resultSet.getString("reason")),
			resultSet.getLong("trade_count"),
			resultSet.getObject("created_at", OffsetDateTime.class)
		);
	}
}
