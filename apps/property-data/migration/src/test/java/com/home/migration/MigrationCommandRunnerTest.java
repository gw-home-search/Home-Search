package com.home.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class MigrationCommandRunnerTest {

	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
	).withDatabaseName("home_search");

	private static DriverManagerDataSource dataSource;

	@BeforeAll
	static void startPostgres() {
		POSTGRES.start();
		dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		dataSource.setDriverClassName(POSTGRES.getDriverClassName());
	}

	@Test
	@DisplayName("info는 실제 database가 home_search가 아니면 중단한다")
	void infoRejectsUnexpectedDatabase() {
		DriverManagerDataSource wrongDatabase = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl().replace("/home_search", "/postgres"),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()
		);
		wrongDatabase.setDriverClassName(POSTGRES.getDriverClassName());

		assertThatThrownBy(() -> new MigrationCommandRunner(wrongDatabase).run(
			new DefaultApplicationArguments("--operation=info")
		))
			.isInstanceOf(MigrationOperationException.class)
			.hasMessageContaining("expected=home_search")
			.hasMessageContaining("actual=postgres");
	}

	@Test
	@DisplayName("info는 version state description checksum을 출력한다")
	void infoPrintsMigrationChecksum() {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		PrintStream original = System.out;
		try {
			System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
			new MigrationCommandRunner(dataSource).run(new DefaultApplicationArguments("--operation=info"));
		}
		finally {
			System.setOut(original);
		}

		assertThat(output.toString(StandardCharsets.UTF_8))
			.contains("version=1 state=Pending description=create clean core schema checksum=");
	}
}
