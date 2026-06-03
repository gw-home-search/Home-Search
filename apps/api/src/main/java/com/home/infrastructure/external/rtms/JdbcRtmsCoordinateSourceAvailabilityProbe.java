package com.home.infrastructure.external.rtms;

import java.util.Properties;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcRtmsCoordinateSourceAvailabilityProbe implements RtmsCoordinateSourceAvailabilityProbe {

	private final JdbcClient jdbcClient;

	JdbcRtmsCoordinateSourceAvailabilityProbe(
		String jdbcUrl,
		String username,
		String password,
		int connectTimeoutSeconds,
		int socketTimeoutSeconds,
		int lockTimeoutMillis,
		int statementTimeoutMillis
	) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.postgresql.Driver");
		dataSource.setUrl(jdbcUrl);
		dataSource.setUsername(username);
		dataSource.setPassword(password);
		dataSource.setConnectionProperties(connectionProperties(
			connectTimeoutSeconds,
			socketTimeoutSeconds,
			lockTimeoutMillis,
			statementTimeoutMillis
		));
		this.jdbcClient = JdbcClient.create(dataSource);
	}

	@Override
	public void verifyAvailable() {
		Boolean hasSnapshot = jdbcClient.sql("""
			SELECT to_regclass('reference.parcel_coordinate_snapshot') IS NOT NULL
			""")
			.query(Boolean.class)
			.single();
		if (!Boolean.TRUE.equals(hasSnapshot)) {
			throw new IllegalStateException(
				"Coordinate source DB must expose reference.parcel_coordinate_snapshot for RTMS ingest preflight"
			);
		}
	}

	private Properties connectionProperties(
		int connectTimeoutSeconds,
		int socketTimeoutSeconds,
		int lockTimeoutMillis,
		int statementTimeoutMillis
	) {
		Properties properties = new Properties();
		properties.setProperty("connectTimeout", Integer.toString(connectTimeoutSeconds));
		properties.setProperty("socketTimeout", Integer.toString(socketTimeoutSeconds));
		properties.setProperty("readOnly", "true");
		properties.setProperty("options", "-c lock_timeout=%d -c statement_timeout=%d"
			.formatted(lockTimeoutMillis, statementTimeoutMillis));
		return properties;
	}
}
