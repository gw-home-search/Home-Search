package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcCoordinateSourceParcelCoordinateRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("Coordinate Source DB에서 PNU coordinate와 geometry를 read-only lookup 한다")
	void findsCoordinateFromCoordinateSourceByPnu() {
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
		JdbcCoordinateSourceParcelCoordinateRepository repository =
			new JdbcCoordinateSourceParcelCoordinateRepository(jdbcClient);

		assertThat(repository.findByPnu(" 1168010300107770001 "))
			.hasValueSatisfying(coordinate -> {
				assertThat(coordinate.latitude()).isEqualByComparingTo(new BigDecimal("37.5012345"));
				assertThat(coordinate.longitude()).isEqualByComparingTo(new BigDecimal("127.0543210"));
				assertThat(coordinate.geometryWkt()).startsWith("MULTIPOLYGON");
			});
	}

	@Test
	@DisplayName("blank PNU는 Coordinate Source DB lookup을 수행하지 않는다")
	void blankPnuReturnsEmpty() {
		JdbcCoordinateSourceParcelCoordinateRepository repository =
			new JdbcCoordinateSourceParcelCoordinateRepository(jdbcClient);

		assertThat(repository.findByPnu(" ")).isEmpty();
	}

	@Test
	@DisplayName("19자리 숫자가 아닌 PNU는 source DB query를 수행하지 않는다")
	void invalidPnuReturnsEmptyWithoutQuery() {
		JdbcClient jdbcClient = mock(JdbcClient.class);
		JdbcCoordinateSourceParcelCoordinateRepository repository =
			new JdbcCoordinateSourceParcelCoordinateRepository(jdbcClient);

		assertThat(repository.findByPnu("11680%")).isEmpty();
		assertThat(repository.findByPnu("116801030010777000")).isEmpty();
		assertThat(repository.findByPnu("11680103001077700010")).isEmpty();
		verifyNoInteractions(jdbcClient);
	}
}
