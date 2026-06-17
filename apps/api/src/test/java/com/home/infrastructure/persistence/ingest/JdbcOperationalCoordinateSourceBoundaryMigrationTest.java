package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcOperationalCoordinateSourceBoundaryMigrationTest extends JdbcMigrationTestSupport {

	@Test
	@DisplayName("API Flyway latest는 운영 DB에서 coordinate source reference tables를 소유하지 않는다")
	void apiFlywayLatestDoesNotOwnCoordinateSourceReferenceTables() {
		flyway(null).clean();
		flyway(null).migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);

		assertThat(regclass("reference.parcel_coordinate_snapshot")).isNull();
		assertThat(regclass("reference.parcel_coordinate_snapshot_stage")).isNull();
		assertThat(regclass("reference.parcel_coordinate_snapshot_publish")).isNull();
		assertThat(regclass("reference.coordinate_snapshot_run")).isNull();
		assertThat(regclass("public.parcel")).isEqualTo("parcel");
		assertThat(regclass("public.complex_display_coordinate")).isEqualTo("complex_display_coordinate");
		assertThat(regclass("public.building_footprint_snapshot")).isEqualTo("building_footprint_snapshot");
	}

	private String regclass(String name) {
		return jdbcClient.sql("SELECT to_regclass(:name)::text")
			.param("name", name)
			.query(String.class)
			.optional()
			.orElse(null);
	}
}
