package com.home.infrastructure.persistence.coordinate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.home.application.coordinate.override.CoordinateOverrideApprovalCommand;
import com.home.domain.coordinate.CoordinatePendingReason;
import com.home.application.coordinate.override.InvalidCoordinateOverrideException;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import com.home.infrastructure.persistence.map.JdbcMapMarkerRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcCoordinateOverrideAdminRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("coordinate-pending 목록은 좌표 없는 parcel과 거래 수를 반환한다")
	void findsCoordinatePendingComplexesWithTradeCount() {
		seedCoordinatePendingComplex();
		JdbcCoordinateOverrideAdminRepository repository = new JdbcCoordinateOverrideAdminRepository(jdbcClient);

		assertThat(repository.findPendingComplexes(20, 0))
			.singleElement()
			.satisfies(pending -> {
				assertThat(pending.parcelId()).isEqualTo(1001L);
				assertThat(pending.complexId()).isEqualTo(501L);
				assertThat(pending.pnu()).isEqualTo("1168010300101400001");
				assertThat(pending.aptSeq()).isEqualTo("APT-501");
				assertThat(pending.aptName()).isEqualTo("Pending Apartment");
				assertThat(pending.reason()).isEqualTo(CoordinatePendingReason.PNU_COORDINATE_MISSING);
				assertThat(pending.tradeCount()).isEqualTo(1L);
			});
	}

	@Test
	@DisplayName("coordinate-pending 목록은 거래가 없는 좌표 미완성 단지를 제외한다")
	void excludesCoordinatePendingComplexesWithoutTrades() {
		seedCoordinatePendingComplexWithoutTrade();
		JdbcCoordinateOverrideAdminRepository repository = new JdbcCoordinateOverrideAdminRepository(jdbcClient);

		assertThat(repository.findPendingComplexes(20, 0)).isEmpty();
	}

	@Test
	@DisplayName("coordinate-pending 목록은 같은 PNU 다중 단지 reason을 반환한다")
	void findsSamePnuMultiComplexReason() {
		seedSamePnuMultiComplexWithoutDisplayCoordinate();
		JdbcCoordinateOverrideAdminRepository repository = new JdbcCoordinateOverrideAdminRepository(jdbcClient);

		assertThat(repository.findPendingComplexes(20, 0))
			.extracting(
				"complexId",
				"reason"
			)
			.containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple(601L, CoordinatePendingReason.SAME_PNU_MULTI_COMPLEX),
				org.assertj.core.groups.Tuple.tuple(602L, CoordinatePendingReason.SAME_PNU_MULTI_COMPLEX)
			);
	}

	@Test
	@DisplayName("coordinate-pending 목록은 offset으로 다음 page를 조회한다")
	void findsPendingComplexesWithOffset() {
		seedSamePnuMultiComplexWithoutDisplayCoordinate();
		JdbcCoordinateOverrideAdminRepository repository = new JdbcCoordinateOverrideAdminRepository(jdbcClient);

		assertThat(repository.findPendingComplexes(1, 1))
			.singleElement()
			.satisfies(pending -> assertThat(pending.complexId()).isEqualTo(602L));
	}

	@Test
	@DisplayName("coordinate-pending 목록은 일부 단지만 display 좌표가 없으면 complex display missing reason을 반환한다")
	void findsComplexDisplayCoordinateMissingReason() {
		seedSamePnuPartialDisplayCoordinate();
		JdbcCoordinateOverrideAdminRepository repository = new JdbcCoordinateOverrideAdminRepository(jdbcClient);

		assertThat(repository.findPendingComplexes(20, 0))
			.singleElement()
			.satisfies(pending -> {
				assertThat(pending.complexId()).isEqualTo(702L);
				assertThat(pending.reason()).isEqualTo(CoordinatePendingReason.COMPLEX_DISPLAY_COORDINATE_MISSING);
			});
	}

	@Test
	@DisplayName("coordinate-pending summary는 전체 사유별 건수를 반환한다")
	void findsPendingSummaryReasonCounts() {
		seedCoordinatePendingSummaryData();
		JdbcCoordinateOverrideAdminRepository repository = new JdbcCoordinateOverrideAdminRepository(jdbcClient);

		var summary = repository.findPendingSummary();

		assertThat(summary.totalCount()).isEqualTo(4L);
		assertThat(summary.count(CoordinatePendingReason.PNU_COORDINATE_MISSING)).isEqualTo(1L);
		assertThat(summary.count(CoordinatePendingReason.SAME_PNU_MULTI_COMPLEX)).isEqualTo(2L);
		assertThat(summary.count(CoordinatePendingReason.COMPLEX_DISPLAY_COORDINATE_MISSING)).isEqualTo(1L);
	}

	@Test
	@DisplayName("coordinate-pending 목록은 같은 PNU 다중 단지에 fallback 좌표만 있으면 same-PNU reason으로 남긴다")
	void keepsSamePnuMultiComplexReasonWhenOnlyFallbackCoordinatesExist() {
		seedSamePnuMultiComplexWithOnlyFallbackCoordinates();
		JdbcCoordinateOverrideAdminRepository repository = new JdbcCoordinateOverrideAdminRepository(jdbcClient);

		assertThat(repository.findPendingComplexes(20, 0))
			.extracting(
				"complexId",
				"reason"
			)
			.containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple(801L, CoordinatePendingReason.SAME_PNU_MULTI_COMPLEX),
				org.assertj.core.groups.Tuple.tuple(802L, CoordinatePendingReason.SAME_PNU_MULTI_COMPLEX)
			);
	}

	@Test
	@DisplayName("approved override는 기존 parcel 좌표를 채워 marker 후보를 복구한다")
	void approvedOverrideUpdatesExistingParcelCoordinate() {
		seedCoordinatePendingComplex();
		JdbcCoordinateOverrideAdminRepository repository = new JdbcCoordinateOverrideAdminRepository(jdbcClient);

		var result = repository.approve(new CoordinateOverrideApprovalCommand(
			"1168010300101400001",
			new BigDecimal("37.5123000"),
			new BigDecimal("127.0456000"),
			"operator verified missing coordinate",
			"test-operator"
		));

		assertThat(result.pnu()).isEqualTo("1168010300101400001");
		assertThat(result.parcelUpdated()).isTrue();
		assertThat(new JdbcMapMarkerRepository(jdbcClient).findComplexMarkers(bounds()))
			.singleElement()
			.satisfies(marker -> {
				assertThat(marker.parcelId()).isEqualTo(1001L);
				assertThat(marker.lat()).isEqualTo(37.5123);
				assertThat(marker.lng()).isEqualTo(127.0456);
			});
	}

	@Test
	@DisplayName("approved override는 이미 좌표가 있는 PNU를 거부한다")
	void approvedOverrideRejectsAlreadyCoordinateReadyParcel() {
		seedSamePnuMultiComplexWithoutDisplayCoordinate();
		JdbcCoordinateOverrideAdminRepository repository = new JdbcCoordinateOverrideAdminRepository(jdbcClient);

		assertThatThrownBy(() -> repository.approve(new CoordinateOverrideApprovalCommand(
			"1168010300101400002",
			new BigDecimal("37.5123000"),
			new BigDecimal("127.0456000"),
			"operator verified missing coordinate",
			"test-operator"
		))).isInstanceOf(InvalidCoordinateOverrideException.class);

		assertThat(approvedOverrideCount("1168010300101400002")).isZero();
	}

	@Test
	@DisplayName("approved override는 존재하지 않는 PNU를 거부한다")
	void approvedOverrideRejectsUnknownPnu() {
		JdbcCoordinateOverrideAdminRepository repository = new JdbcCoordinateOverrideAdminRepository(jdbcClient);

		assertThatThrownBy(() -> repository.approve(new CoordinateOverrideApprovalCommand(
			"1168010300101400999",
			new BigDecimal("37.5123000"),
			new BigDecimal("127.0456000"),
			"operator verified missing coordinate",
			"test-operator"
		))).isInstanceOf(InvalidCoordinateOverrideException.class);

		assertThat(approvedOverrideCount("1168010300101400999")).isZero();
	}

	private int approvedOverrideCount(String pnu) {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM parcel_coordinate_override
			WHERE pnu = :pnu
			  AND status = 'APPROVED'
			""")
			.param("pnu", pnu)
			.query(Integer.class)
			.single();
	}

	private void seedCoordinatePendingComplex() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400001', 'Pending address', NULL, NULL)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Pending Apartment', 740)
			""").update();
		jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id,
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status,
			    processed_at
			)
			VALUES (90001, 'RTMS', 'pending-raw-1', '11680', '202512', 1, '{}', 'pending-hash-1', 'NORMALIZED', now())
			""").update();
		jdbcClient.sql("""
			INSERT INTO trade (
			    id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES (
			    9001,
			    501,
			    DATE '2025-12-01',
			    125000,
			    12,
			    84.93,
			    'RTMS',
			    'pending-trade-1',
			    'COMPLEX-PK-501',
			    'APT-501',
			    90001
			)
			""").update();
	}

	private void seedCoordinatePendingComplexWithoutTrade() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1002, 1, '1168010300101400002', 'Pending address without trade', NULL, NULL)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES (502, 1002, 'COMPLEX-PK-502', 'APT-502', 'Pending Apartment Without Trade', 740)
			""").update();
	}

	private void seedSamePnuMultiComplexWithoutDisplayCoordinate() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (2001, 1, '1168010300101400002', 'Same PNU address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES
			    (601, 2001, 'COMPLEX-PK-601', 'APT-601', 'Same PNU A', 300),
			    (602, 2001, 'COMPLEX-PK-602', 'APT-602', 'Same PNU B', 400)
			""").update();
		insertRawAndTrade(90002L, 601L, "same-pnu-601", "COMPLEX-PK-601", "APT-601");
		insertRawAndTrade(90003L, 602L, "same-pnu-602", "COMPLEX-PK-602", "APT-602");
	}

	private void seedSamePnuPartialDisplayCoordinate() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (3001, 1, '1168010300101400003', 'Partial display coordinate address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES
			    (701, 3001, 'COMPLEX-PK-701', 'APT-701', 'Display Ready A', 300),
			    (702, 3001, 'COMPLEX-PK-702', 'APT-702', 'Display Missing B', 400)
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
			VALUES (701, 37.5130, 127.0460, 'BUILDING_FOOTPRINT', 90, 'trusted display coordinate')
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
			VALUES (702, 37.5123, 127.0456, 'PARCEL_FALLBACK', 60, 'parcel fallback marker coordinate')
			""").update();
		insertRawAndTrade(90004L, 701L, "partial-display-701", "COMPLEX-PK-701", "APT-701");
		insertRawAndTrade(90005L, 702L, "partial-display-702", "COMPLEX-PK-702", "APT-702");
	}

	private void seedSamePnuMultiComplexWithOnlyFallbackCoordinates() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (4001, 1, '1168010300101400004', 'Fallback display coordinate address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES
			    (801, 4001, 'COMPLEX-PK-801', 'APT-801', 'Fallback A', 300),
			    (802, 4001, 'COMPLEX-PK-802', 'APT-802', 'Fallback B', 400)
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
			VALUES
			    (801, 37.5123, 127.0456, 'PARCEL_FALLBACK', 60, 'parcel fallback marker coordinate'),
			    (802, 37.5123, 127.0456, 'PARCEL_FALLBACK', 80, 'parcel fallback marker coordinate')
			""").update();
		insertRawAndTrade(90006L, 801L, "fallback-display-801", "COMPLEX-PK-801", "APT-801");
		insertRawAndTrade(90007L, 802L, "fallback-display-802", "COMPLEX-PK-802", "APT-802");
	}

	private void seedCoordinatePendingSummaryData() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES
			    (5001, 1, '1168010300101400101', 'Missing coordinate address', NULL, NULL),
			    (5002, 1, '1168010300101400102', 'Same PNU address', 37.5123, 127.0456),
			    (5003, 1, '1168010300101400103', 'Partial display coordinate address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES
			    (1501, 5001, 'SUMMARY-PK-1501', 'APT-1501', 'Summary Missing Coordinate', 100),
			    (1601, 5002, 'SUMMARY-PK-1601', 'APT-1601', 'Summary Same PNU A', 100),
			    (1602, 5002, 'SUMMARY-PK-1602', 'APT-1602', 'Summary Same PNU B', 100),
			    (1701, 5003, 'SUMMARY-PK-1701', 'APT-1701', 'Summary Display Ready', 100),
			    (1702, 5003, 'SUMMARY-PK-1702', 'APT-1702', 'Summary Display Missing', 100)
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
			VALUES (1701, 37.5130, 127.0460, 'BUILDING_FOOTPRINT', 90, 'trusted display coordinate')
			""").update();
		insertRawAndTrade(91001L, 1501L, "summary-missing-1501", "SUMMARY-PK-1501", "APT-1501");
		insertRawAndTrade(91002L, 1601L, "summary-same-1601", "SUMMARY-PK-1601", "APT-1601");
		insertRawAndTrade(91003L, 1602L, "summary-same-1602", "SUMMARY-PK-1602", "APT-1602");
		insertRawAndTrade(91004L, 1701L, "summary-display-1701", "SUMMARY-PK-1701", "APT-1701");
		insertRawAndTrade(91005L, 1702L, "summary-display-1702", "SUMMARY-PK-1702", "APT-1702");
	}

	private void insertRawAndTrade(Long rawId, Long complexId, String sourceKey, String complexPk, String aptSeq) {
		jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id,
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status,
			    processed_at
			)
			VALUES (:rawId, 'RTMS', :sourceKey, '11680', '202512', 1, '{}', :payloadHash, 'NORMALIZED', now())
			""")
			.param("rawId", rawId)
			.param("sourceKey", sourceKey + "-raw")
			.param("payloadHash", sourceKey + "-hash")
			.update();
		jdbcClient.sql("""
			INSERT INTO trade (
			    id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES (
			    :tradeId,
			    :complexId,
			    DATE '2025-12-01',
			    125000,
			    12,
			    84.93,
			    'RTMS',
			    :sourceKey,
			    :complexPk,
			    :aptSeq,
			    :rawId
			)
			""")
			.param("tradeId", rawId + 1000)
			.param("complexId", complexId)
			.param("sourceKey", sourceKey)
			.param("complexPk", complexPk)
			.param("aptSeq", aptSeq)
			.param("rawId", rawId)
			.update();
	}

	private com.home.application.map.ComplexMarkerQuery bounds() {
		return new com.home.application.map.ComplexMarkerQuery(
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
}
