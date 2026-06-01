package com.home.infrastructure.persistence.coordinate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.util.List;

import com.home.application.coordinate.ComplexDisplayCoordinateCommand;
import com.home.application.coordinate.ComplexDisplayCoordinateProjectionService;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcComplexDisplayCoordinateProjectionRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("projection은 좌표가 없는 모든 complex에 parcel fallback 표시 좌표를 생성한다")
	void projectsFallbackCoordinatesForEveryComplexWithoutDisplayCoordinate() {
		seedSingleAndAmbiguousMultiComplexParcels();
		JdbcComplexDisplayCoordinateProjectionRepository repository = new JdbcComplexDisplayCoordinateProjectionRepository(
			jdbcClient
		);
		ComplexDisplayCoordinateProjectionService service = new ComplexDisplayCoordinateProjectionService(repository);

		var result = service.project(10);

		assertThat(result.processed()).isEqualTo(3);
		assertThat(result.parcelFallback()).isEqualTo(3);
		assertThat(findDisplayRows())
			.extracting(
				DisplayCoordinateRow::complexId,
				DisplayCoordinateRow::coordinateSource,
				DisplayCoordinateRow::confidence,
				DisplayCoordinateRow::latitude,
				DisplayCoordinateRow::longitude
			)
			.containsExactly(
				tuple(501L, "PARCEL_FALLBACK", 70, bd("37.5123000"), bd("127.0456000")),
				tuple(601L, "PARCEL_FALLBACK", 40, bd("37.5124000"), bd("127.0457000")),
				tuple(602L, "PARCEL_FALLBACK", 40, bd("37.5124000"), bd("127.0457000"))
			);
		assertThat(repository.findProjectionTargets(10)).isEmpty();
		assertThat(displayCoordinateCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("projection은 resolved 건물 링크로 fallback 좌표를 승급하고 건물 좌표를 fallback으로 덮지 않는다")
	void upgradesFallbackToBuildingFootprintAndDoesNotDowngrade() {
		seedBuildingUpgradeParcel();
		JdbcComplexDisplayCoordinateProjectionRepository repository = new JdbcComplexDisplayCoordinateProjectionRepository(
			jdbcClient
		);
		ComplexDisplayCoordinateProjectionService service = new ComplexDisplayCoordinateProjectionService(repository);

		var result = service.project(10);

		assertThat(result.processed()).isEqualTo(1);
		assertThat(result.buildingFootprint()).isEqualTo(1);
		assertThat(findDisplayRows())
			.extracting(
				DisplayCoordinateRow::complexId,
				DisplayCoordinateRow::buildingFootprintId,
				DisplayCoordinateRow::coordinateSource,
				DisplayCoordinateRow::confidence,
				DisplayCoordinateRow::latitude,
				DisplayCoordinateRow::longitude
			)
			.containsExactly(tuple(701L, 9101L, "BUILDING_FOOTPRINT", 88, bd("37.6010000"), bd("127.1010000")));

		repository.saveDisplayCoordinate(new ComplexDisplayCoordinateCommand(
			701L,
			null,
			bd("37.5125000"),
			bd("127.0458000"),
			"PARCEL_FALLBACK",
			70,
			"direct fallback retry"
		));

		assertThat(findDisplayRows())
			.extracting(
				DisplayCoordinateRow::complexId,
				DisplayCoordinateRow::buildingFootprintId,
				DisplayCoordinateRow::coordinateSource,
				DisplayCoordinateRow::confidence,
				DisplayCoordinateRow::latitude,
				DisplayCoordinateRow::longitude
			)
			.containsExactly(tuple(701L, 9101L, "BUILDING_FOOTPRINT", 88, bd("37.6010000"), bd("127.1010000")));
	}

	private void seedSingleAndAmbiguousMultiComplexParcels() {
		seedRegion();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES
			    (1001, 1, '1168010300101400001', 'Single address', 37.5123, 127.0456),
			    (1002, 1, '1168010300101400002', 'Concurrent address', 37.5124, 127.0457)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name)
			VALUES
			    (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Single Apartment'),
			    (601, 1002, 'COMPLEX-PK-601', 'APT-601', 'Tower A'),
			    (602, 1002, 'COMPLEX-PK-602', 'APT-602', 'Tower B')
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, reason)
			VALUES (1002, '1168010300101400002', 'AMBIGUOUS', 'building dong candidates are ambiguous')
			""").update();
	}

	private void seedBuildingUpgradeParcel() {
		seedRegion();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1003, 1, '1168010300101400003', 'Building upgrade address', 37.5125, 127.0458)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name)
			VALUES (701, 1003, 'COMPLEX-PK-701', 'APT-701', 'Upgrade Apartment')
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES (701, 37.5125, 127.0458, 'PARCEL_FALLBACK', 70, 'existing fallback')
			""").update();
		jdbcClient.sql("""
			INSERT INTO building_footprint_snapshot (
			    id,
			    pnu,
			    building_name,
			    dong_name,
			    source_building_key,
			    centroid_lat,
			    centroid_lng,
			    source,
			    snapshot_version
			)
			VALUES (
			    9101,
			    '1168010300101400003',
			    'Upgrade Apartment',
			    '101동',
			    'TEST-BLD-9101',
			    37.6010,
			    127.1010,
			    'TEST',
			    '2026-06'
			)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_building_link (
			    complex_id,
			    building_footprint_id,
			    status,
			    confidence,
			    reason,
			    source
			)
			VALUES (701, 9101, 'RESOLVED', 88, 'resolved building link', 'TEST')
			""").update();
	}

	private void seedRegion() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
	}

	private List<DisplayCoordinateRow> findDisplayRows() {
		return jdbcClient.sql("""
			SELECT
			    complex_id,
			    building_footprint_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence
			FROM complex_display_coordinate
			ORDER BY complex_id
			""")
			.query((resultSet, rowNumber) -> new DisplayCoordinateRow(
				resultSet.getLong("complex_id"),
				nullableLong(resultSet, "building_footprint_id"),
				resultSet.getBigDecimal("latitude"),
				resultSet.getBigDecimal("longitude"),
				resultSet.getString("coordinate_source"),
				resultSet.getInt("confidence")
			))
			.list();
	}

	private long displayCoordinateCount() {
		return jdbcClient.sql("SELECT count(*) FROM complex_display_coordinate")
			.query(Long.class)
			.single();
	}

	private Long nullableLong(java.sql.ResultSet resultSet, String columnName) throws java.sql.SQLException {
		long value = resultSet.getLong(columnName);
		if (resultSet.wasNull()) {
			return null;
		}
		return value;
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	private record DisplayCoordinateRow(
		Long complexId,
		Long buildingFootprintId,
		BigDecimal latitude,
		BigDecimal longitude,
		String coordinateSource,
		int confidence
	) {
	}
}
