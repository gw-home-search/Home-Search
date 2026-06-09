package com.home.infrastructure.persistence.ingest;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

import com.home.application.coordinate.lookup.ParcelCoordinate;
import com.home.application.coordinate.lookup.ParcelCoordinateOverrideRepository;

import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcParcelCoordinateOverrideRepository implements ParcelCoordinateOverrideRepository {

	private final JdbcClient jdbcClient;

	JdbcParcelCoordinateOverrideRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public Optional<ParcelCoordinate> findApprovedByPnu(String pnu) {
		String normalizedPnu = trimToNull(pnu);
		if (normalizedPnu == null) {
			return Optional.empty();
		}
		return jdbcClient.sql("""
			SELECT latitude, longitude
			FROM parcel_coordinate_override
			WHERE pnu = :pnu
			  AND status = 'APPROVED'
			ORDER BY approved_at DESC, id DESC
			LIMIT 1
			""")
			.param("pnu", normalizedPnu)
			.query((resultSet, rowNumber) -> new ParcelCoordinate(
				resultSet.getObject("latitude", BigDecimal.class),
				resultSet.getObject("longitude", BigDecimal.class)
			))
			.optional();
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
