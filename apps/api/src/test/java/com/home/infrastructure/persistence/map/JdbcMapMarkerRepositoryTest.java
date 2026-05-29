package com.home.infrastructure.persistence.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDate;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;
import com.home.infrastructure.web.map.dto.ComplexMarkerResponse;
import com.home.infrastructure.web.map.dto.ComplexMarkersRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcMapMarkerRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("bounds query는 latest trade amount와 unit sum이 있는 parcel complex marker를 반환한다")
	void boundsQueryReturnsComplexMarkers() {
		seedMapData();
		JdbcMapMarkerRepository repository = new JdbcMapMarkerRepository(jdbcClient);

		var markers = repository.findComplexMarkers(request(null, null));

		assertThat(markers)
			.extracting(
				ComplexMarkerResponse::parcelId,
				ComplexMarkerResponse::lat,
				ComplexMarkerResponse::lng,
				ComplexMarkerResponse::latestDealAmount,
				ComplexMarkerResponse::unitCntSum
			)
			.containsExactly(tuple(1001L, 37.5123, 127.0456, 125000L, 860L));
	}

	@Test
	@DisplayName("price eok filter는 10,000 KRW trade amount unit으로 변환된다")
	void priceEokFiltersUseTradeAmountUnits() {
		seedMapData();
		JdbcMapMarkerRepository repository = new JdbcMapMarkerRepository(jdbcClient);

		assertThat(repository.findComplexMarkers(request(12.0, 13.0))).hasSize(1);
		assertThat(repository.findComplexMarkers(request(13.0, null))).isEmpty();
	}

	@Test
	@DisplayName("bounds query는 canceled trade를 latest amount 후보에서 제외한다")
	void boundsQueryExcludesCanceledTradeFromLatestAmount() {
		seedMapData();
		jdbcClient.sql("""
			UPDATE trade
			SET deleted_at = now()
			WHERE source_key = 'rtms-map-marker-2'
			""").update();
		JdbcMapMarkerRepository repository = new JdbcMapMarkerRepository(jdbcClient);

		assertThat(repository.findComplexMarkers(request(null, null)))
			.singleElement()
			.extracting(ComplexMarkerResponse::latestDealAmount)
			.isEqualTo(111000L);
	}

	private ComplexMarkersRequest request(Double priceEokMin, Double priceEokMax) {
		return new ComplexMarkersRequest(
			37.45,
			126.85,
			37.70,
			127.20,
			null,
			null,
			priceEokMin,
			priceEokMax,
			null,
			null,
			null,
			null
		);
	}

	private void seedMapData() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES
			    (1001, 1, '1168010300101400001', 'Inside bounds', 37.5123, 127.0456),
			    (1002, 1, '1168010300101400002', 'Outside bounds', 37.8123, 127.4456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES
			    (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Sample Apartment A', 740),
			    (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Sample Apartment B', 120),
			    (503, 1002, 'COMPLEX-PK-503', 'APT-503', 'Out-of-bounds Apartment', 300)
			""").update();
		Long rawId = insertRawIngest("rtms-map-marker-1");
		insertTrade(rawId, 501L, LocalDate.of(2025, 11, 1), 111000L, "rtms-map-marker-1", "COMPLEX-PK-501");
		insertTrade(rawId, 502L, LocalDate.of(2025, 12, 1), 125000L, "rtms-map-marker-2", "COMPLEX-PK-502");
		insertTrade(rawId, 503L, LocalDate.of(2025, 12, 2), 200000L, "rtms-map-marker-3", "COMPLEX-PK-503");
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
			VALUES ('RTMS', :sourceKey, '11680', '202512', 1, '{}', :payloadHash, 'NORMALIZED')
			RETURNING id
			""")
			.param("sourceKey", sourceKey)
			.param("payloadHash", "payload-hash-" + sourceKey)
			.query(Long.class)
			.single();
	}

	private void insertTrade(
		Long rawId,
		Long complexId,
		LocalDate dealDate,
		Long dealAmount,
		String sourceKey,
		String complexPk
	) {
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
			    :dealAmount,
			    12,
			    84.93,
			    '101',
			    'RTMS',
			    :sourceKey,
			    :complexPk,
			    'APT-501'
			)
			""")
			.param("rawId", rawId)
			.param("complexId", complexId)
			.param("dealDate", dealDate)
			.param("dealAmount", dealAmount)
			.param("sourceKey", sourceKey)
			.param("complexPk", complexPk)
			.update();
	}
}
