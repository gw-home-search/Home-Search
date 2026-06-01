package com.home.infrastructure.persistence.ingest;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcParcelCoordinateSnapshotRepository implements ParcelCoordinateSnapshotRepository {

	private final JdbcClient jdbcClient;

	JdbcParcelCoordinateSnapshotRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public Optional<ParcelCoordinate> findByPnu(String pnu) {
		String normalizedPnu = trimToNull(pnu);
		if (normalizedPnu == null || !validPnu(normalizedPnu)) {
			return Optional.empty();
		}
		return findExactPnu(normalizedPnu)
			.or(() -> findSameBonbunGroup(normalizedPnu));
	}

	private Optional<ParcelCoordinate> findExactPnu(String pnu) {
		return jdbcClient.sql("""
			SELECT
			    latitude,
			    longitude,
			    ST_AsText(geom) AS geometry_wkt
			FROM reference.parcel_coordinate_snapshot
			WHERE pnu = :pnu
			""")
			.param("pnu", pnu)
			.query((resultSet, rowNumber) -> new ParcelCoordinate(
				resultSet.getObject("latitude", BigDecimal.class),
				resultSet.getObject("longitude", BigDecimal.class),
				resultSet.getString("geometry_wkt")
			))
			.optional();
	}

	private Optional<ParcelCoordinate> findSameBonbunGroup(String pnu) {
		String sameBonbunPrefix = pnu.substring(0, 15);
		return jdbcClient.sql("""
			WITH unioned AS (
			    SELECT ST_Multi(ST_Union(geom)) AS geom
			    FROM reference.parcel_coordinate_snapshot
			    WHERE pnu >= :sameBonbunStart
			      AND pnu <= :sameBonbunEnd
			      AND pnu <> :pnu
			)
			SELECT
			    round(ST_Y(ST_Centroid(geom))::numeric, 7) AS latitude,
			    round(ST_X(ST_Centroid(geom))::numeric, 7) AS longitude,
			    ST_AsText(geom) AS geometry_wkt
			FROM unioned
			WHERE geom IS NOT NULL
			""")
			.param("sameBonbunStart", sameBonbunPrefix + "0000")
			.param("sameBonbunEnd", sameBonbunPrefix + "9999")
			.param("pnu", pnu)
			.query((resultSet, rowNumber) -> new ParcelCoordinate(
				resultSet.getObject("latitude", BigDecimal.class),
				resultSet.getObject("longitude", BigDecimal.class),
				resultSet.getString("geometry_wkt")
			))
			.optional();
	}

	private boolean validPnu(String value) {
		return value.matches("\\d{19}");
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
