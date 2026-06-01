package com.home.infrastructure.persistence.coordinate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.home.application.coordinate.BuildingFootprintCandidate;
import com.home.application.coordinate.ComplexCoordinateCaseCandidate;
import com.home.application.coordinate.ComplexCoordinateCaseStatus;
import com.home.application.coordinate.ComplexCoordinateCaseUpdate;
import com.home.application.coordinate.ComplexCoordinateExceptionRepository;
import com.home.application.coordinate.ComplexCoordinateParcelTargets;
import com.home.application.coordinate.ComplexCoordinateReadinessRepository;
import com.home.application.coordinate.ComplexCoordinateTarget;
import com.home.application.coordinate.ResolvedDisplayCoordinate;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcComplexCoordinateExceptionRepository
	implements ComplexCoordinateExceptionRepository, ComplexCoordinateReadinessRepository {

	private final JdbcClient jdbcClient;

	public JdbcComplexCoordinateExceptionRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<ComplexCoordinateCaseCandidate> findExceptionCaseCandidates(int limit) {
		if (limit < 1) {
			return List.of();
		}
		return jdbcClient.sql("""
			SELECT p.id AS parcel_id
			FROM parcel p
			JOIN complex c ON c.parcel_id = p.id
			WHERE NOT EXISTS (
			    SELECT 1
			    FROM complex_coordinate_case existing_case
			    WHERE existing_case.parcel_id = p.id
			)
			GROUP BY p.id
			HAVING count(c.id) > 1
			ORDER BY p.id
			LIMIT :limit
			""")
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new ComplexCoordinateCaseCandidate(resultSet.getLong("parcel_id")))
			.list();
	}

	@Override
	public List<Long> findPendingCaseParcelIds(int limit) {
		if (limit < 1) {
			return List.of();
		}
		return jdbcClient.sql("""
			SELECT parcel_id
			FROM complex_coordinate_case
			WHERE status = 'PENDING'
			ORDER BY id
			LIMIT :limit
			""")
			.param("limit", limit)
			.query(Long.class)
			.list();
	}

	@Override
	public void markCaseFailed(Long parcelId, String reason) {
		saveCaseUpdate(new ComplexCoordinateCaseUpdate(
			parcelId,
			ComplexCoordinateCaseStatus.FAILED,
			reason
		));
	}

	@Override
	public void saveCaseUpdate(ComplexCoordinateCaseUpdate update) {
		Objects.requireNonNull(update, "update is required");
		jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (
			    parcel_id,
			    pnu,
			    status,
			    relation_type,
			    relation_confidence,
			    reason,
			    checked_at,
			    updated_at
			)
			SELECT
			    p.id,
			    p.pnu,
			    :status,
			    :relationType,
			    :relationConfidence,
			    :reason,
			    now(),
			    now()
			FROM parcel p
			WHERE p.id = :parcelId
			ON CONFLICT (parcel_id) DO UPDATE SET
			    status = EXCLUDED.status,
			    relation_type = COALESCE(EXCLUDED.relation_type, complex_coordinate_case.relation_type),
			    relation_confidence = COALESCE(EXCLUDED.relation_confidence, complex_coordinate_case.relation_confidence),
			    reason = EXCLUDED.reason,
			    checked_at = now(),
			    updated_at = now()
			""")
			.param("parcelId", update.parcelId())
			.param("status", update.status().name())
			.param("relationType", update.relationType() == null ? null : update.relationType().name())
			.param(
				"relationConfidence",
				update.relationConfidence() == null ? null : update.relationConfidence().name()
			)
			.param("reason", update.reason())
			.update();
	}

	@Override
	public Optional<ComplexCoordinateParcelTargets> findParcelTargets(Long parcelId) {
		Objects.requireNonNull(parcelId, "parcelId is required");
		List<TargetRow> rows = jdbcClient.sql("""
			SELECT
			    p.id AS parcel_id,
			    p.pnu,
			    c.id AS complex_id,
			    c.apt_seq,
			    c.name,
			    COALESCE(
			        string_agg(DISTINCT btrim(t.apt_dong), '|' ORDER BY btrim(t.apt_dong))
			            FILTER (
			                WHERE t.deleted_at IS NULL
			                  AND t.apt_dong IS NOT NULL
			                  AND btrim(t.apt_dong) <> ''
			            ),
			        ''
			    ) AS apt_dongs
			FROM parcel p
			JOIN complex c ON c.parcel_id = p.id
			LEFT JOIN trade t ON t.complex_id = c.id
			WHERE p.id = :parcelId
			GROUP BY p.id, p.pnu, c.id, c.apt_seq, c.name
			ORDER BY c.id
			""")
			.param("parcelId", parcelId)
			.query((resultSet, rowNumber) -> new TargetRow(
				resultSet.getLong("parcel_id"),
				resultSet.getString("pnu"),
				resultSet.getLong("complex_id"),
				resultSet.getString("apt_seq"),
				resultSet.getString("name"),
				resultSet.getString("apt_dongs")
			))
			.list();
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		ArrayList<ComplexCoordinateTarget> targets = new ArrayList<>();
		for (TargetRow row : rows) {
			targets.add(new ComplexCoordinateTarget(
				row.complexId(),
				row.aptSeq(),
				row.name(),
				parseAptDongs(row.aptDongs())
			));
		}
		TargetRow first = rows.get(0);
		return Optional.of(new ComplexCoordinateParcelTargets(first.parcelId(), first.pnu(), targets));
	}

	@Override
	public List<BuildingFootprintCandidate> findBuildingFootprintsByPnu(String pnu) {
		Objects.requireNonNull(pnu, "pnu is required");
		return jdbcClient.sql("""
			SELECT
			    id,
			    pnu,
			    building_name,
			    dong_name,
			    centroid_lat,
			    centroid_lng
			FROM building_footprint_snapshot
			WHERE pnu = :pnu
			ORDER BY id
			""")
			.param("pnu", pnu)
			.query((resultSet, rowNumber) -> new BuildingFootprintCandidate(
				resultSet.getLong("id"),
				resultSet.getString("pnu"),
				resultSet.getString("building_name"),
				resultSet.getString("dong_name"),
				resultSet.getBigDecimal("centroid_lat"),
				resultSet.getBigDecimal("centroid_lng")
			))
			.list();
	}

	@Override
	public void saveResolvedDisplayCoordinate(ResolvedDisplayCoordinate coordinate) {
		Objects.requireNonNull(coordinate, "coordinate is required");
		saveBuildingLink(coordinate);
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
			""")
			.param("complexId", coordinate.complexId())
			.param("buildingFootprintId", coordinate.buildingFootprintId())
			.param("latitude", coordinate.latitude())
			.param("longitude", coordinate.longitude())
			.param("coordinateSource", coordinate.coordinateSource())
			.param("confidence", coordinate.confidence())
			.param("reason", coordinate.reason())
			.update();
	}

	private void saveBuildingLink(ResolvedDisplayCoordinate coordinate) {
		jdbcClient.sql("""
			INSERT INTO complex_building_link (
			    complex_id,
			    building_footprint_id,
			    status,
			    confidence,
			    reason,
			    source,
			    updated_at
			)
			VALUES (
			    :complexId,
			    :buildingFootprintId,
			    'RESOLVED',
			    :confidence,
			    :reason,
			    :source,
			    now()
			)
			ON CONFLICT DO NOTHING
			""")
			.param("complexId", coordinate.complexId())
			.param("buildingFootprintId", coordinate.buildingFootprintId())
			.param("confidence", coordinate.confidence())
			.param("reason", coordinate.reason())
			.param("source", coordinate.coordinateSource())
			.update();
	}

	private Set<String> parseAptDongs(String raw) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		LinkedHashSet<String> values = new LinkedHashSet<>();
		for (String value : raw.split("\\|")) {
			if (!value.isBlank()) {
				values.add(value);
			}
		}
		return values;
	}

	private record TargetRow(
		Long parcelId,
		String pnu,
		Long complexId,
		String aptSeq,
		String name,
		String aptDongs
	) {
	}
}
