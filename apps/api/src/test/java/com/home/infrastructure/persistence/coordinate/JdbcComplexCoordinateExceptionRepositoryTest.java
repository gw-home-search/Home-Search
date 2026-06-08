package com.home.infrastructure.persistence.coordinate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.home.application.complex.ComplexRelationClassifier;
import com.home.application.complex.ComplexRelationRepository;
import com.home.application.coordinate.footprint.BuildingFootprintCandidate;
import com.home.application.coordinate.footprint.BuildingFootprintImportCandidate;
import com.home.application.coordinate.footprint.BuildingFootprintSource;
import com.home.application.coordinate.caseflow.ComplexCoordinateCaseStatus;
import com.home.application.coordinate.caseflow.ComplexCoordinateExceptionService;
import com.home.application.coordinate.display.ResolvedDisplayCoordinate;
import com.home.infrastructure.persistence.complex.JdbcComplexRelationRepository;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.home.application.coordinate.identity.ComplexCoordinateIdentityVerifier;

class JdbcComplexCoordinateExceptionRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("같은 PNU에 여러 complex가 있을 때만 좌표 보정 case 후보로 조회한다")
	void findsOnlyMultiComplexParcelCandidates() {
		seedSingleComplexParcel();
		seedConcurrentComplexParcel();
		JdbcComplexCoordinateExceptionRepository repository = new JdbcComplexCoordinateExceptionRepository(jdbcClient);

		assertThat(repository.findExceptionCaseCandidates(10))
			.extracting(candidate -> candidate.parcelId())
			.containsExactly(1002L);
	}

	@Test
	@DisplayName("좌표 readiness는 PENDING coordinate case parcelId만 순서와 limit에 맞게 조회한다")
	void findsOnlyPendingCoordinateCaseParcelIds() {
		seedSingleComplexParcel();
		seedConcurrentComplexParcel();
		seedResolvedCaseParcel();
		jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, reason)
			VALUES
			    (1001, '1168010300101400001', 'PENDING', 'pending first'),
			    (1002, '1168010300101400002', 'PENDING', 'pending second'),
			    (1003, '1168010300101400003', 'RESOLVED', 'resolved')
			""").update();
		JdbcComplexCoordinateExceptionRepository repository = new JdbcComplexCoordinateExceptionRepository(jdbcClient);

		assertThat(repository.findPendingCaseParcelIds(1)).containsExactly(1001L);
		assertThat(repository.findPendingCaseParcelIds(10)).containsExactly(1001L, 1002L);
	}

	@Test
	@DisplayName("좌표 readiness는 backoff 이전의 FAILED/UNAVAILABLE case만 재시도 후보로 조회한다")
	void findsOnlyRetryableCasesBeforeBackoff() {
		seedSingleComplexParcel();
		seedConcurrentComplexParcel();
		seedResolvedCaseParcel();
		jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, reason, checked_at)
			VALUES
			    (1001, '1168010300101400001', 'FAILED', 'stale failure', now() - INTERVAL '2 days'),
			    (1002, '1168010300101400002', 'FAILED', 'fresh failure', now()),
			    (1003, '1168010300101400003', 'UNAVAILABLE', 'stale unavailable', now() - INTERVAL '2 days')
			""").update();
		JdbcComplexCoordinateExceptionRepository repository = new JdbcComplexCoordinateExceptionRepository(jdbcClient);

		java.time.Instant retryBefore = java.time.Instant.now().minus(java.time.Duration.ofDays(1));
		assertThat(repository.findRetryableCaseParcelIds(10, retryBefore)).containsExactly(1001L, 1003L);
		assertThat(repository.findRetryableCaseParcelIds(0, retryBefore)).isEmpty();
	}

	@Test
	@DisplayName("building footprint 저장은 좌표 범위가 벗어난 row를 건너뛰고 유효 row를 보존한다")
	void saveBuildingFootprintsSkipsInvalidCoordinateRows() {
		JdbcComplexCoordinateExceptionRepository repository = new JdbcComplexCoordinateExceptionRepository(jdbcClient);

		repository.saveBuildingFootprints(java.util.List.of(
			importFootprint("1168010300101400002", "101동", "37.5010000", "127.0010000"),
			importFootprint("1168010300101400002", "999동", "41.0000000", "127.9990000")
		));

		assertThat(footprintCount("1168010300101400002")).isEqualTo(1L);
		assertThat(repository.findBuildingFootprintsByPnu("1168010300101400002"))
			.extracting(BuildingFootprintCandidate::dongName)
			.containsExactly("101동");
	}

	@Test
	@DisplayName("같은 PNU의 동시 존재 complex는 apt_dong과 건물 동명으로 서로 다른 표시 좌표를 저장한다")
	void storesDifferentDisplayCoordinatesForConcurrentComplexesUnderSamePnu() {
		seedConcurrentComplexParcel();
		Long rawId = insertRawIngest("coordinate-exception");
		insertTrade(rawId, 601L, LocalDate.of(2024, 1, 1), "rtms-coordinate-601-1", "101");
		insertTrade(rawId, 601L, LocalDate.of(2025, 1, 1), "rtms-coordinate-601-2", "101");
		insertTrade(rawId, 602L, LocalDate.of(2024, 6, 1), "rtms-coordinate-602-1", "201");
		insertTrade(rawId, 602L, LocalDate.of(2025, 6, 1), "rtms-coordinate-602-2", "201");
		JdbcComplexCoordinateExceptionRepository repository = new JdbcComplexCoordinateExceptionRepository(jdbcClient);
		insertBuildingFootprint(new BuildingFootprintCandidate(
			9001L,
			"1168010300101400002",
			"Tower A",
			"101동",
			bd("37.5010000"),
			bd("127.0010000")
		));
		insertBuildingFootprint(new BuildingFootprintCandidate(
			9002L,
			"1168010300101400002",
			"Tower B",
			"201동",
			bd("37.5020000"),
			bd("127.0020000")
		));
		ComplexRelationRepository relationRepository = new JdbcComplexRelationRepository(jdbcClient);
		ComplexCoordinateExceptionService service = new ComplexCoordinateExceptionService(
			repository,
			relationRepository,
			new ComplexRelationClassifier()
		);

		service.stageExceptionCases(10);
		var resolution = service.resolveExceptionCase(1002L);

		assertThat(resolution.status()).isEqualTo(ComplexCoordinateCaseStatus.RESOLVED);
		assertThat(findResolvedDisplayCoordinates())
			.extracting(
				ResolvedDisplayCoordinate::complexId,
				ResolvedDisplayCoordinate::latitude,
				ResolvedDisplayCoordinate::longitude
			)
			.containsExactly(
				tuple(601L, bd("37.5010000"), bd("127.0010000")),
				tuple(602L, bd("37.5020000"), bd("127.0020000"))
			);
		assertThat(caseStatus(1002L)).isEqualTo("RESOLVED");
	}

	@Test
	@DisplayName("VWorld에서 가져온 여러 동 footprint를 저장하고 단지별 집계 표시 좌표로 resolve한다")
	void savesFetchedVworldFootprintsAndStoresAggregatedDisplayCoordinates() {
		seedConcurrentComplexParcel();
		Long rawId = insertRawIngest("coordinate-aggregate");
		insertTrade(rawId, 601L, LocalDate.of(2025, 1, 1), "rtms-coordinate-601-1", "1001");
		insertTrade(rawId, 601L, LocalDate.of(2025, 1, 2), "rtms-coordinate-601-2", "1002");
		insertTrade(rawId, 602L, LocalDate.of(2025, 1, 3), "rtms-coordinate-602-1", "1006");
		insertTrade(rawId, 602L, LocalDate.of(2025, 1, 4), "rtms-coordinate-602-2", "1008");
		JdbcComplexCoordinateExceptionRepository repository = new JdbcComplexCoordinateExceptionRepository(jdbcClient);
		BuildingFootprintSource source = pnu -> java.util.List.of(
			importFootprint(pnu, "1001동", "37.6875000", "126.7780000"),
			importFootprint(pnu, "1002동", "37.6885000", "126.7786000"),
			importFootprint(pnu, "1006동", "37.6890000", "126.7779000"),
			importFootprint(pnu, "1008동", "37.6896000", "126.7781000")
		);
		ComplexCoordinateExceptionService service = new ComplexCoordinateExceptionService(
			repository,
			new JdbcComplexRelationRepository(jdbcClient),
			new ComplexRelationClassifier(),
			com.home.application.coordinate.identity.ComplexCoordinateIdentityVerifier.trusting(),
			source
		);

		service.stageExceptionCases(10);
		var resolution = service.resolveExceptionCase(1002L);

		assertThat(resolution.status()).isEqualTo(ComplexCoordinateCaseStatus.RESOLVED);
		assertThat(footprintCount("1168010300101400002")).isEqualTo(4);
		assertThat(findResolvedDisplayCoordinates())
			.extracting(
				ResolvedDisplayCoordinate::complexId,
				ResolvedDisplayCoordinate::latitude,
				ResolvedDisplayCoordinate::longitude,
				ResolvedDisplayCoordinate::reason
			)
			.containsExactly(
				tuple(601L, bd("37.6880000"), bd("126.7783000"), "apt_dong matched building dong_name aggregate footprint_count=2"),
				tuple(602L, bd("37.6893000"), bd("126.7780000"), "apt_dong matched building dong_name aggregate footprint_count=2")
			);
		assertThat(caseStatus(1002L)).isEqualTo("RESOLVED");
	}

	private void seedSingleComplexParcel() {
		seedRegion();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400001', 'Single address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name)
			VALUES (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Single Apartment')
			""").update();
	}

	private void seedConcurrentComplexParcel() {
		seedRegion();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1002, 1, '1168010300101400002', 'Concurrent address', 37.5124, 127.0457)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name)
			VALUES
			    (601, 1002, 'COMPLEX-PK-601', 'APT-601', 'Tower A'),
			    (602, 1002, 'COMPLEX-PK-602', 'APT-602', 'Tower B')
			""").update();
	}

	private void seedResolvedCaseParcel() {
		seedRegion();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1003, 1, '1168010300101400003', 'Resolved address', 37.5125, 127.0458)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name)
			VALUES (701, 1003, 'COMPLEX-PK-701', 'APT-701', 'Resolved Apartment')
			""").update();
	}

	private void seedRegion() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			ON CONFLICT (code) DO NOTHING
			""").update();
	}

	private Long insertRawIngest(String sourceKey) {
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
			VALUES ('RTMS', :sourceKey, '11680', '202501', 1, '{}', :payloadHash, 'NORMALIZED')
			RETURNING id
			""")
			.param("sourceKey", sourceKey)
			.param("payloadHash", "payload-hash-" + sourceKey)
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

	private String caseStatus(Long parcelId) {
		return jdbcClient.sql("""
			SELECT status
			FROM complex_coordinate_case
			WHERE parcel_id = :parcelId
			""")
			.param("parcelId", parcelId)
			.query(String.class)
			.single();
	}

	private void insertBuildingFootprint(BuildingFootprintCandidate footprint) {
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
			.param("id", footprint.id())
			.param("pnu", footprint.pnu())
			.param("buildingName", footprint.buildingName())
			.param("dongName", footprint.dongName())
			.param("sourceBuildingKey", "TEST-BLD-" + footprint.id())
			.param("latitude", footprint.latitude())
			.param("longitude", footprint.longitude())
			.update();
	}

	private BuildingFootprintImportCandidate importFootprint(
		String pnu,
		String dongName,
		String latitude,
		String longitude
	) {
		return new BuildingFootprintImportCandidate(
			pnu,
			"중산마을",
			dongName,
			"VWORLD-" + pnu + "-" + dongName,
			bd(latitude),
			bd(longitude),
			"VWORLD_WFS",
			"LIVE"
		);
	}

	private Long footprintCount(String pnu) {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM building_footprint_snapshot
			WHERE pnu = :pnu
			""")
			.param("pnu", pnu)
			.query(Long.class)
			.single();
	}

	private java.util.List<ResolvedDisplayCoordinate> findResolvedDisplayCoordinates() {
		return jdbcClient.sql("""
			SELECT
			    complex_id,
			    building_footprint_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			FROM complex_display_coordinate
			ORDER BY complex_id
			""")
			.query((resultSet, rowNumber) -> new ResolvedDisplayCoordinate(
				resultSet.getLong("complex_id"),
				resultSet.getLong("building_footprint_id"),
				resultSet.getBigDecimal("latitude"),
				resultSet.getBigDecimal("longitude"),
				resultSet.getString("coordinate_source"),
				resultSet.getInt("confidence"),
				resultSet.getString("reason")
			))
			.list();
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}
}
