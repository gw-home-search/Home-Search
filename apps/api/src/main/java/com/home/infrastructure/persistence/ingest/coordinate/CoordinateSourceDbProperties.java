package com.home.infrastructure.persistence.ingest.coordinate;

import java.util.Properties;

public record CoordinateSourceDbProperties(
	String jdbcUrl,
	String username,
	String password,
	int connectTimeoutSeconds,
	int socketTimeoutSeconds,
	int lockTimeoutMillis,
	int statementTimeoutMillis,
	boolean readOnly
) {

	public boolean enabled() {
		return jdbcUrl != null && !jdbcUrl.isBlank();
	}

	public Properties connectionProperties() {
		Properties properties = new Properties();
		properties.setProperty("connectTimeout", Integer.toString(connectTimeoutSeconds));
		properties.setProperty("socketTimeout", Integer.toString(socketTimeoutSeconds));
		properties.setProperty("readOnly", Boolean.toString(readOnly));
		properties.setProperty("options", "-c lock_timeout=%d -c statement_timeout=%d"
			.formatted(lockTimeoutMillis, statementTimeoutMillis));
		return properties;
	}
}
