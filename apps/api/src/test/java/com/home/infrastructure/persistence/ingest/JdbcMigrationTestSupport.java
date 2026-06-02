package com.home.infrastructure.persistence.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class JdbcMigrationTestSupport extends JdbcPostgresContainerSupport {

	private static final PostgreSQLContainer<?> POSTGRES = newPostgisContainer();

	static {
		POSTGRES.start();
	}

	@BeforeEach
	protected void initializeMigrationDatabase() {
		initializeJdbc(POSTGRES);
	}
}
