package com.home.infrastructure.persistence.coordinate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import com.home.application.coordinate.override.CoordinateOverrideAdminRepository;
import com.home.application.coordinate.override.CoordinateOverrideApprovalCommand;
import com.home.application.coordinate.override.CoordinateOverrideApprovalResult;
import com.home.application.coordinate.override.CoordinatePendingComplex;
import com.home.application.coordinate.override.CoordinatePendingReason;
import com.home.application.coordinate.override.CoordinatePendingSummary;
import com.home.application.coordinate.override.InvalidCoordinateOverrideException;

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcCoordinateOverrideAdminRepository implements CoordinateOverrideAdminRepository {

	private final JdbcClient jdbcClient;

	JdbcCoordinateOverrideAdminRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<CoordinatePendingComplex> findPendingComplexes(int limit, int offset) {
		return jdbcClient.sql("""
			WITH multi_parcel AS (
			    SELECT parcel_id
			    FROM complex
			    GROUP BY parcel_id
			    HAVING count(*) > 1
			),
			pending_candidate AS (
			    SELECT
			        p.id AS parcel_id,
			        c.id AS complex_id,
			        p.pnu,
			        c.apt_seq,
			        COALESCE(c.trade_name, c.name) AS apt_name,
			        p.address,
			        c.created_at,
			        'PNU_COORDINATE_MISSING' AS reason
			    FROM parcel p
			    JOIN complex c ON c.parcel_id = p.id
			    WHERE (p.latitude IS NULL OR p.longitude IS NULL)
			      AND EXISTS (
			          SELECT 1
			          FROM trade t
			          WHERE t.complex_id = c.id
			            AND t.deleted_at IS NULL
			      )
			    UNION ALL
			    SELECT
			        p.id AS parcel_id,
			        c.id AS complex_id,
			        p.pnu,
			        c.apt_seq,
			        COALESCE(c.trade_name, c.name) AS apt_name,
			        p.address,
			        c.created_at,
			        'SAME_PNU_MULTI_COMPLEX' AS reason
			    FROM multi_parcel
			    JOIN parcel p ON p.id = multi_parcel.parcel_id
			    JOIN complex c ON c.parcel_id = p.id
			    WHERE p.latitude IS NOT NULL
			      AND p.longitude IS NOT NULL
			      AND EXISTS (
			          SELECT 1
			          FROM trade t
			          WHERE t.complex_id = c.id
			            AND t.deleted_at IS NULL
			      )
			      AND NOT EXISTS (
			          SELECT 1
			          FROM complex parcel_complex
			          JOIN complex_display_coordinate display_coordinate
			            ON display_coordinate.complex_id = parcel_complex.id
			           AND display_coordinate.coordinate_source = 'BUILDING_FOOTPRINT'
			           AND display_coordinate.confidence >= 80
			          WHERE parcel_complex.parcel_id = p.id
			      )
			    UNION ALL
			    SELECT
			        p.id AS parcel_id,
			        c.id AS complex_id,
			        p.pnu,
			        c.apt_seq,
			        COALESCE(c.trade_name, c.name) AS apt_name,
			        p.address,
			        c.created_at,
			        'COMPLEX_DISPLAY_COORDINATE_MISSING' AS reason
			    FROM multi_parcel
			    JOIN parcel p ON p.id = multi_parcel.parcel_id
			    JOIN complex c ON c.parcel_id = p.id
			    WHERE p.latitude IS NOT NULL
			      AND p.longitude IS NOT NULL
			      AND EXISTS (
			          SELECT 1
			          FROM trade t
			          WHERE t.complex_id = c.id
			            AND t.deleted_at IS NULL
			      )
			      AND EXISTS (
			          SELECT 1
			          FROM complex parcel_complex
			          JOIN complex_display_coordinate display_coordinate
			            ON display_coordinate.complex_id = parcel_complex.id
			           AND display_coordinate.coordinate_source = 'BUILDING_FOOTPRINT'
			           AND display_coordinate.confidence >= 80
			          WHERE parcel_complex.parcel_id = p.id
			      )
			      AND NOT EXISTS (
			          SELECT 1
			          FROM complex_display_coordinate display_coordinate
			          WHERE display_coordinate.complex_id = c.id
			            AND display_coordinate.coordinate_source = 'BUILDING_FOOTPRINT'
			            AND display_coordinate.confidence >= 80
			      )
			),
			pending_base AS (
			    SELECT *
			    FROM pending_candidate
			    ORDER BY created_at DESC, parcel_id, complex_id
			    LIMIT :limit
			    OFFSET :offset
			)
			SELECT
			    pending_base.parcel_id,
			    pending_base.complex_id,
			    pending_base.pnu,
			    pending_base.apt_seq,
			    pending_base.apt_name,
			    pending_base.address,
			    pending_base.reason,
			    active_trade.trade_count,
			    pending_base.created_at
			FROM pending_base
			JOIN LATERAL (
			    SELECT count(*) AS trade_count
			    FROM trade t
			    WHERE t.complex_id = pending_base.complex_id
			      AND t.deleted_at IS NULL
			) active_trade ON true
			ORDER BY pending_base.created_at DESC, pending_base.parcel_id, pending_base.complex_id
			""")
			.param("limit", limit)
			.param("offset", offset)
			.query(this::mapPendingComplex)
			.list();
	}

	@Override
	public CoordinatePendingSummary findPendingSummary() {
		return jdbcClient.sql("""
			WITH multi_parcel AS (
			    SELECT parcel_id
			    FROM complex
			    GROUP BY parcel_id
			    HAVING count(*) > 1
			),
			pending_candidate AS (
			    SELECT
			        'PNU_COORDINATE_MISSING' AS reason
			    FROM parcel p
			    JOIN complex c ON c.parcel_id = p.id
			    WHERE (p.latitude IS NULL OR p.longitude IS NULL)
			      AND EXISTS (
			          SELECT 1
			          FROM trade t
			          WHERE t.complex_id = c.id
			            AND t.deleted_at IS NULL
			      )
			    UNION ALL
			    SELECT
			        'SAME_PNU_MULTI_COMPLEX' AS reason
			    FROM multi_parcel
			    JOIN parcel p ON p.id = multi_parcel.parcel_id
			    JOIN complex c ON c.parcel_id = p.id
			    WHERE p.latitude IS NOT NULL
			      AND p.longitude IS NOT NULL
			      AND EXISTS (
			          SELECT 1
			          FROM trade t
			          WHERE t.complex_id = c.id
			            AND t.deleted_at IS NULL
			      )
			      AND NOT EXISTS (
			          SELECT 1
			          FROM complex parcel_complex
			          JOIN complex_display_coordinate display_coordinate
			            ON display_coordinate.complex_id = parcel_complex.id
			           AND display_coordinate.coordinate_source = 'BUILDING_FOOTPRINT'
			           AND display_coordinate.confidence >= 80
			          WHERE parcel_complex.parcel_id = p.id
			      )
			    UNION ALL
			    SELECT
			        'COMPLEX_DISPLAY_COORDINATE_MISSING' AS reason
			    FROM multi_parcel
			    JOIN parcel p ON p.id = multi_parcel.parcel_id
			    JOIN complex c ON c.parcel_id = p.id
			    WHERE p.latitude IS NOT NULL
			      AND p.longitude IS NOT NULL
			      AND EXISTS (
			          SELECT 1
			          FROM trade t
			          WHERE t.complex_id = c.id
			            AND t.deleted_at IS NULL
			      )
			      AND EXISTS (
			          SELECT 1
			          FROM complex parcel_complex
			          JOIN complex_display_coordinate display_coordinate
			            ON display_coordinate.complex_id = parcel_complex.id
			           AND display_coordinate.coordinate_source = 'BUILDING_FOOTPRINT'
			           AND display_coordinate.confidence >= 80
			          WHERE parcel_complex.parcel_id = p.id
			      )
			      AND NOT EXISTS (
			          SELECT 1
			          FROM complex_display_coordinate display_coordinate
			          WHERE display_coordinate.complex_id = c.id
			            AND display_coordinate.coordinate_source = 'BUILDING_FOOTPRINT'
			            AND display_coordinate.confidence >= 80
			      )
			)
			SELECT
			    count(*) AS total_count,
			    count(*) FILTER (WHERE reason = 'PNU_COORDINATE_MISSING') AS pnu_coordinate_missing_count,
			    count(*) FILTER (WHERE reason = 'SAME_PNU_MULTI_COMPLEX') AS same_pnu_multi_complex_count,
			    count(*) FILTER (WHERE reason = 'COMPLEX_DISPLAY_COORDINATE_MISSING') AS complex_display_coordinate_missing_count
			FROM pending_candidate
			""")
			.query(this::mapPendingSummary)
			.single();
	}

	@Override
	public CoordinateOverrideApprovalResult approve(CoordinateOverrideApprovalCommand command) {
		if (!canApproveParcelCoordinate(command.pnu())) {
			throw new InvalidCoordinateOverrideException(
				"coordinate override requires a PNU coordinate missing parcel with active trades"
			);
		}
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

	private boolean canApproveParcelCoordinate(String pnu) {
		return Boolean.TRUE.equals(jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM parcel p
			    JOIN complex c ON c.parcel_id = p.id
			    WHERE p.pnu = :pnu
			      AND (p.latitude IS NULL OR p.longitude IS NULL)
			      AND EXISTS (
			          SELECT 1
			          FROM trade t
			          WHERE t.complex_id = c.id
			            AND t.deleted_at IS NULL
			      )
			)
			""")
			.param("pnu", pnu)
			.query(Boolean.class)
			.single());
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

	private CoordinatePendingSummary mapPendingSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CoordinatePendingSummary(
			resultSet.getLong("total_count"),
			resultSet.getLong("pnu_coordinate_missing_count"),
			resultSet.getLong("same_pnu_multi_complex_count"),
			resultSet.getLong("complex_display_coordinate_missing_count")
		);
	}
}
