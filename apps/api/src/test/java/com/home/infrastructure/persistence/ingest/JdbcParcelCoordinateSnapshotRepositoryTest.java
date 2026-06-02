package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcParcelCoordinateSnapshotRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("VWorld SHP coordinate snapshot은 DB에서 PNU coordinate와 geometry를 resolve한다")
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
	@DisplayName("exact PNU가 없고 같은 본번의 부번 snapshot이 있으면 union 중심 좌표를 반환한다")
	void findsSameBonbunCoordinateWhenExactPnuIsMissing() {
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
		insertSnapshot(
			runId,
			"1168010300107770001",
			37.5015000,
			127.0545000,
			"MULTIPOLYGON(((127.0540 37.5010,127.0550 37.5010,127.0550 37.5020,127.0540 37.5020,127.0540 37.5010)))"
		);
		insertSnapshot(
			runId,
			"1168010300107770002",
			37.5015000,
			127.0565000,
			"MULTIPOLYGON(((127.0560 37.5010,127.0570 37.5010,127.0570 37.5020,127.0560 37.5020,127.0560 37.5010)))"
		);
		JdbcParcelCoordinateSnapshotRepository repository = new JdbcParcelCoordinateSnapshotRepository(jdbcClient);

		assertThat(repository.findByPnu("1168010300107770000"))
			.hasValueSatisfying(coordinate -> {
				assertThat(coordinate.latitude()).isEqualByComparingTo(new BigDecimal("37.5015000"));
				assertThat(coordinate.longitude()).isEqualByComparingTo(new BigDecimal("127.0555000"));
				assertThat(coordinate.geometryWkt()).startsWith("MULTIPOLYGON");
			});
	}

	@Test
	@DisplayName("blank PNU는 coordinate snapshot을 반환하지 않는다")
	void blankPnuReturnsEmpty() {
		JdbcParcelCoordinateSnapshotRepository repository = new JdbcParcelCoordinateSnapshotRepository(jdbcClient);

		assertThat(repository.findByPnu(" ")).isEmpty();
	}

	private void insertSnapshot(Long runId, String pnu, double latitude, double longitude, String geometryWkt) {
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
			    :pnu,
			    '11',
			    :latitude,
			    :longitude,
			    ST_SetSRID(ST_Point(:longitude, :latitude), 4326),
			    ST_GeomFromText(:geometryWkt, 4326),
			    '20260508',
			    'LSMD_CONT_LDREG_11_202605.shp',
			    :runId
			)
			""")
			.param("pnu", pnu)
			.param("latitude", latitude)
			.param("longitude", longitude)
			.param("geometryWkt", geometryWkt)
			.param("runId", runId)
			.update();
	}
}
