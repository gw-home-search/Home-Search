package com.home.infrastructure.persistence.coordinate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.home.application.complex.ComplexRelationClassifier;
import com.home.application.coordinate.ComplexCoordinateReadinessResult;
import com.home.application.coordinate.ComplexCoordinateReadinessService;
import com.home.application.coordinate.ComplexDisplayCoordinateProjectionService;
import com.home.infrastructure.persistence.complex.JdbcComplexRelationRepository;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import com.home.infrastructure.persistence.map.JdbcMapMarkerRepository;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcComplexCoordinateReadinessIntegrationTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("coordinate readiness는 단일 fallback과 다중 단지 building 좌표를 full coverage로 준비한다")
	void preparesFullDisplayCoordinateCoverage() {
		seedCoordinateReadinessData();
		JdbcComplexCoordinateExceptionRepository coordinateRepository = new JdbcComplexCoordinateExceptionRepository(
			jdbcClient
		);
		ComplexCoordinateReadinessService service = new ComplexCoordinateReadinessService(
			new com.home.application.coordinate.ComplexCoordinateExceptionService(
				coordinateRepository,
				new JdbcComplexRelationRepository(jdbcClient),
				new ComplexRelationClassifier()
			),
			coordinateRepository,
			new ComplexDisplayCoordinateProjectionService(new JdbcComplexDisplayCoordinateProjectionRepository(jdbcClient))
		);

		ComplexCoordinateReadinessResult result = service.prepare(10, 10, 10);

		assertThat(result.staged()).isEqualTo(2);
		assertThat(result.pending()).isEqualTo(2);
		assertThat(result.resolved()).isEqualTo(1);
		assertThat(result.ambiguous()).isEqualTo(1);
		assertThat(result.projectedBuildingFootprint()).isZero();
		assertThat(result.projectedParcelFallback()).isEqualTo(3);
		assertThat(caseStatuses())
			.containsExactly(
				tuple(1002L, "RESOLVED"),
				tuple(1003L, "AMBIGUOUS")
			);
		assertThat(displayRows())
			.extracting(
				DisplayRow::complexId,
				DisplayRow::coordinateSource,
				DisplayRow::confidence,
				DisplayRow::latitude,
				DisplayRow::longitude
			)
			.containsExactly(
				tuple(501L, "PARCEL_FALLBACK", 70, bd("37.5123000"), bd("127.0456000")),
				tuple(601L, "BUILDING_FOOTPRINT", 90, bd("37.5010000"), bd("127.0010000")),
				tuple(602L, "BUILDING_FOOTPRINT", 90, bd("37.5020000"), bd("127.0020000")),
				tuple(701L, "PARCEL_FALLBACK", 40, bd("37.5125000"), bd("127.0458000")),
				tuple(702L, "PARCEL_FALLBACK", 40, bd("37.5125000"), bd("127.0458000"))
			);
		assertThat(new JdbcMapMarkerRepository(jdbcClient).findComplexMarkers(bounds()))
			.extracting(ComplexMarkerResponse::parcelId, ComplexMarkerResponse::lat, ComplexMarkerResponse::lng)
			.contains(
				tuple(1002L, 37.5020, 127.0020),
				tuple(1003L, 37.5125, 127.0458)
			);

		ComplexCoordinateReadinessResult secondRun = service.prepare(10, 10, 10);

		assertThat(secondRun).isEqualTo(ComplexCoordinateReadinessResult.empty());
		assertThat(displayRowCount()).isEqualTo(5);
	}

	private void seedCoordinateReadinessData() {
		seedRegion();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES
			    (1001, 1, '1168010300101400001', 'Single address', 37.5123, 127.0456),
			    (1002, 1, '1168010300101400002', 'Resolved multi address', 37.5124, 127.0457),
			    (1003, 1, '1168010300101400003', 'Ambiguous multi address', 37.5125, 127.0458)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name)
			VALUES
			    (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Single Apartment'),
			    (601, 1002, 'COMPLEX-PK-601', 'APT-601', 'Tower A'),
			    (602, 1002, 'COMPLEX-PK-602', 'APT-602', 'Tower B'),
			    (701, 1003, 'COMPLEX-PK-701', 'APT-701', 'Ambiguous A'),
			    (702, 1003, 'COMPLEX-PK-702', 'APT-702', 'Ambiguous B')
			""").update();
		Long rawId = insertRawIngest();
		insertTrade(rawId, 601L, LocalDate.of(2024, 1, 1), "rtms-readiness-601-1", "101");
		insertTrade(rawId, 601L, LocalDate.of(2025, 1, 1), "rtms-readiness-601-2", "101");
		insertTrade(rawId, 602L, LocalDate.of(2024, 6, 1), "rtms-readiness-602-1", "201");
		insertTrade(rawId, 602L, LocalDate.of(2025, 6, 1), "rtms-readiness-602-2", "201");
		insertTrade(rawId, 701L, LocalDate.of(2024, 1, 1), "rtms-readiness-701-1", "301");
		insertTrade(rawId, 701L, LocalDate.of(2025, 1, 1), "rtms-readiness-701-2", "301");
		insertTrade(rawId, 702L, LocalDate.of(2024, 6, 1), "rtms-readiness-702-1", "401");
		insertTrade(rawId, 702L, LocalDate.of(2025, 6, 1), "rtms-readiness-702-2", "401");
		insertBuildingFootprint(9001L, "1168010300101400002", "Tower A", "101동", "37.5010000", "127.0010000");
		insertBuildingFootprint(9002L, "1168010300101400002", "Tower B", "201동", "37.5020000", "127.0020000");
		insertBuildingFootprint(9101L, "1168010300101400003", "Ambiguous A", "301동", "37.5030000", "127.0030000");
		insertBuildingFootprint(9102L, "1168010300101400003", "Ambiguous A Annex", "301", "37.5040000", "127.0040000");
		insertBuildingFootprint(9103L, "1168010300101400003", "Ambiguous B", "401동", "37.5050000", "127.0050000");
	}

	private void seedRegion() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
	}

	private Long insertRawIngest() {
		return jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status
			)
			VALUES ('RTMS', 'coordinate-readiness', '11680', '202501', 1, '{}', 'payload-hash-readiness', 'NORMALIZED')
			RETURNING id
			""")
			.query(Long.class)
			.single();
	}

	private void insertTrade(Long rawId, Long complexId, LocalDate dealDate, String sourceKey, String aptDong) {
		jdbcClient.sql("""
			INSERT INTO trade (
			    raw_ingest_id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq
			)
			VALUES (
			    :rawId,
			    :complexId,
			    :dealDate,
			    125000,
			    12,
			    84.93,
			    :aptDong,
			    'RTMS',
			    :sourceKey,
			    :complexPk,
			    :aptSeq
			)
			""")
			.param("rawId", rawId)
			.param("complexId", complexId)
			.param("dealDate", dealDate)
			.param("aptDong", aptDong)
			.param("sourceKey", sourceKey)
			.param("complexPk", "COMPLEX-PK-" + complexId)
			.param("aptSeq", "APT-" + complexId)
			.update();
	}

	private void insertBuildingFootprint(
		Long id,
		String pnu,
		String buildingName,
		String dongName,
		String latitude,
		String longitude
	) {
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
			    :id,
			    :pnu,
			    :buildingName,
			    :dongName,
			    :sourceBuildingKey,
			    :latitude,
			    :longitude,
			    'TEST',
			    '2026-06'
			)
			""")
			.param("id", id)
			.param("pnu", pnu)
			.param("buildingName", buildingName)
			.param("dongName", dongName)
			.param("sourceBuildingKey", "TEST-BLD-" + id)
			.param("latitude", new BigDecimal(latitude))
			.param("longitude", new BigDecimal(longitude))
			.update();
	}

	private List<org.assertj.core.groups.Tuple> caseStatuses() {
		return jdbcClient.sql("""
			SELECT parcel_id, status
			FROM complex_coordinate_case
			ORDER BY parcel_id
			""")
			.query((resultSet, rowNumber) -> tuple(
				resultSet.getLong("parcel_id"),
				resultSet.getString("status")
			))
			.list();
	}

	private List<DisplayRow> displayRows() {
		return jdbcClient.sql("""
			SELECT complex_id, latitude, longitude, coordinate_source, confidence
			FROM complex_display_coordinate
			ORDER BY complex_id
			""")
			.query((resultSet, rowNumber) -> new DisplayRow(
				resultSet.getLong("complex_id"),
				resultSet.getBigDecimal("latitude"),
				resultSet.getBigDecimal("longitude"),
				resultSet.getString("coordinate_source"),
				resultSet.getInt("confidence")
			))
			.list();
	}

	private long displayRowCount() {
		return jdbcClient.sql("SELECT count(*) FROM complex_display_coordinate")
			.query(Long.class)
			.single();
	}

	private ComplexMarkersRequest bounds() {
		return new ComplexMarkersRequest(
			37.45,
			126.85,
			37.70,
			127.20,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	private record DisplayRow(
		Long complexId,
		BigDecimal latitude,
		BigDecimal longitude,
		String coordinateSource,
		int confidence
	) {
	}
}
