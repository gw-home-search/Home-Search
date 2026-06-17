package com.home.sourcedata;

import static org.assertj.core.api.Assertions.assertThat;

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
		assertThat(Path.of("src/main/resources/db/migration/geo-enrichment/V1__create_geo_enrichment_schema.sql"))
			.exists();
	}
}
