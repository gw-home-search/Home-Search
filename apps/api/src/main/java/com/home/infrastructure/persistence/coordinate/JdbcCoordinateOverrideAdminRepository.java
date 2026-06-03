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

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcCoordinateOverrideAdminRepository implements CoordinateOverrideAdminRepository {

	private final JdbcClient jdbcClient;

	JdbcCoordinateOverrideAdminRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<CoordinatePendingComplex> findPendingComplexes(int limit) {
		return jdbcClient.sql("""
			SELECT
			    p.id AS parcel_id,
			    c.id AS complex_id,
			    p.pnu,
			    c.apt_seq,
			    COALESCE(c.trade_name, c.name) AS apt_name,
			    p.address,
			    count(t.id) AS trade_count,
			    c.created_at
			FROM parcel p
			JOIN complex c ON c.parcel_id = p.id
			LEFT JOIN trade t ON t.complex_id = c.id AND t.deleted_at IS NULL
			WHERE p.latitude IS NULL OR p.longitude IS NULL
			GROUP BY
			    p.id,
			    c.id,
			    p.pnu,
			    c.apt_seq,
			    c.trade_name,
			    c.name,
			    p.address,
			    c.created_at
			ORDER BY count(t.id) DESC, c.created_at DESC, p.id, c.id
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
			resultSet.getLong("trade_count"),
			resultSet.getObject("created_at", OffsetDateTime.class)
		);
	}
}
