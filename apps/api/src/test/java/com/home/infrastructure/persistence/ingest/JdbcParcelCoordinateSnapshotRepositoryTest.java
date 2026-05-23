package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcParcelCoordinateSnapshotRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("VWorld SHP coordinate snapshot resolves PNU coordinates and geometry from DB")
	void findsCoordinateSnapshotByPnu() {
		Long runId = jdbcClient.sql("""
			INSERT INTO reference.coordinate_snapshot_run (
			    snapshot_version,
			    source_dir,
			    source_srid,
			    target_srid,
			    status
			)
			VALUES ('20260508', '/coordinate-input', 5186, 4326, 'PASSED')
			RETURNING id
			""")
			.query(Long.class)
			.single();
		jdbcClient.sql("""
			INSERT INTO reference.parcel_coordinate_snapshot (
			    pnu,
			    region_code,
			    latitude,
			    longitude,
			    point,
			    geom,
			    snapshot_version,
			    source_file,
			    run_id
			)
			VALUES (
			    '1168010300107770001',
			    '11',
			    37.5012345,
			    127.0543210,
			    ST_SetSRID(ST_Point(127.0543210, 37.5012345), 4326),
			    ST_GeomFromText(
			        'MULTIPOLYGON(((127.0540 37.5010,127.0546 37.5010,127.0546 37.5015,127.0540 37.5015,127.0540 37.5010)))',
			        4326
			    ),
			    '20260508',
			    'LSMD_CONT_LDREG_11_202605.shp',
			    :runId
			)
			""")
			.param("runId", runId)
			.update();
		JdbcParcelCoordinateSnapshotRepository repository = new JdbcParcelCoordinateSnapshotRepository(jdbcClient);

		assertThat(repository.findByPnu(" 1168010300107770001 "))
			.hasValueSatisfying(coordinate -> {
				assertThat(coordinate.latitude()).isEqualByComparingTo(new BigDecimal("37.5012345"));
				assertThat(coordinate.longitude()).isEqualByComparingTo(new BigDecimal("127.0543210"));
				assertThat(coordinate.geometryWkt()).startsWith("MULTIPOLYGON");
			});
	}

	@Test
	@DisplayName("blank PNU never returns a coordinate snapshot")
	void blankPnuReturnsEmpty() {
		JdbcParcelCoordinateSnapshotRepository repository = new JdbcParcelCoordinateSnapshotRepository(jdbcClient);

		assertThat(repository.findByPnu(" ")).isEmpty();
	}
}
