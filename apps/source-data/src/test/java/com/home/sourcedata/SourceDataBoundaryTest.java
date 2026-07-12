package com.home.sourcedata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceDataBoundaryTest {

	@Test
	@DisplayName("source-data app은 coordinate source와 geo enrichment migration을 소유한다")
	void ownsCoordinateSourceAndGeoEnrichmentMigrations() {
		assertThat(SourceDataBoundary.APP_NAME).isEqualTo("home-search-source-data");
		assertThat(Path.of("src/main/resources/db/migration/coordinate-source/V1__create_coordinate_source_schema.sql"))
			.exists();
		assertThat(Path.of("src/main/resources/db/migration/coordinate-source/V3__create_geo_enrichment_schema.sql"))
			.exists();
		assertThat(SourceDataBoundary.GEO_ENRICHMENT_MIGRATION)
			.isEqualTo(SourceDataBoundary.COORDINATE_SOURCE_MIGRATION);
	}

	@Test
	@DisplayName("coordinate role adoption은 legacy object owner와 runtime 최소 권한을 분리한다")
	void adoptsLegacyCoordinateSourceObjectOwnership() throws IOException {
		String script = Files.readString(Path.of("ops/init-coordinate-source-roles.sh"));

		assertThat(script)
			.contains("\\getenv migrator_password SOURCE_MIGRATOR_DB_PASSWORD")
			.contains("\\getenv importer_password SOURCE_IMPORTER_DB_PASSWORD")
			.contains("\\getenv reader_password COORDINATE_READER_DB_PASSWORD")
			.doesNotContain("--set=migrator_password")
			.contains("--dbname home_search_coordinate_source")
			.contains("ALTER SCHEMA reference OWNER TO home_search_coordinate_migrator")
			.contains("ALTER TABLE %I.%I OWNER TO home_search_coordinate_migrator")
			.contains("ALTER SEQUENCE %I.%I OWNER TO home_search_coordinate_migrator")
			.contains("GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA reference TO home_search_coordinate_importer")
			.contains("REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE reference.flyway_schema_history FROM home_search_coordinate_importer")
			.contains("GRANT SELECT ON ALL TABLES IN SCHEMA reference TO home_search_coordinate_reader")
			.contains("ALTER DEFAULT PRIVILEGES FOR ROLE home_search_coordinate_migrator");
	}
}
