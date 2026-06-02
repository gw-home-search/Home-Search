package com.home.infrastructure.persistence.ingest;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcCoordinateSourceParcelCoordinateRepository implements ParcelCoordinateSourceRepository {

	private static final String PNU_PATTERN = "\\d{19}";

	private final JdbcClient jdbcClient;

	public JdbcCoordinateSourceParcelCoordinateRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public Optional<ParcelCoordinate> findByPnu(String pnu) {
		String normalizedPnu = trimToNull(pnu);
		if (normalizedPnu == null || !normalizedPnu.matches(PNU_PATTERN)) {
			return Optional.empty();
		}
		return jdbcClient.sql("""
			SELECT
			    latitude,
			    longitude,
			    ST_AsText(geom) AS geometry_wkt
			FROM reference.parcel_coordinate_snapshot
			WHERE pnu = :pnu
			""")
			.param("pnu", normalizedPnu)
			.query((resultSet, rowNumber) -> new ParcelCoordinate(
				resultSet.getObject("latitude", BigDecimal.class),
				resultSet.getObject("longitude", BigDecimal.class),
				resultSet.getString("geometry_wkt")
			))
			.optional();
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
