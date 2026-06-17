package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcGeoEnrichmentMigrationTest extends JdbcMigrationTestSupport {

	@Test
	@DisplayName("geo enrichment Flyway는 VWorld WFS raw/cache schema를 별도 DB용으로 생성한다")
	void geoEnrichmentFlywayCreatesVworldWfsRawCacheSchema() {
		flyway(null, "classpath:db/migration/geo-enrichment", "public", "geo_enrichment").clean();
		flyway(null, "classpath:db/migration/geo-enrichment", "public", "geo_enrichment").migrate();
		jdbcClient = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);

		assertThat(regclass("geo_enrichment.vworld_wfs_footprint_cache"))
			.isEqualTo("geo_enrichment.vworld_wfs_footprint_cache");
		assertThat(indexExists("geo_enrichment.ix_vworld_wfs_footprint_cache_geom")).isTrue();
		assertThat(constraintExists("uq_vworld_wfs_footprint_cache_source_key")).isTrue();
	}

	private String regclass(String name) {
		return jdbcClient.sql("SELECT to_regclass(:name)::text")
			.param("name", name)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	private boolean indexExists(String name) {
		return jdbcClient.sql("SELECT to_regclass(:name) IS NOT NULL")
			.param("name", name)
			.query(Boolean.class)
			.single();
	}

	private boolean constraintExists(String name) {
		return jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM pg_constraint
			    WHERE conname = :name
			)
			""")
			.param("name", name)
			.query(Boolean.class)
			.single();
	}
}
