package com.home.infrastructure.persistence.ingest;

record CoordinateSourceDbProperties(
	String jdbcUrl,
	String username,
	String password
) {

	boolean enabled() {
		return jdbcUrl != null && !jdbcUrl.isBlank();
	}
}
