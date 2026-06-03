package com.home.infrastructure.persistence.coordinate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.home.application.coordinate.CoordinateOverrideApprovalCommand;
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

		assertThat(repository.findPendingComplexes(20))
			.singleElement()
			.satisfies(pending -> {
				assertThat(pending.parcelId()).isEqualTo(1001L);
				assertThat(pending.complexId()).isEqualTo(501L);
				assertThat(pending.pnu()).isEqualTo("1168010300101400001");
				assertThat(pending.aptSeq()).isEqualTo("APT-501");
				assertThat(pending.aptName()).isEqualTo("Pending Apartment");
				assertThat(pending.tradeCount()).isEqualTo(1L);
			});
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

	private com.home.infrastructure.web.map.dto.ComplexMarkersRequest bounds() {
		return new com.home.infrastructure.web.map.dto.ComplexMarkersRequest(
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
