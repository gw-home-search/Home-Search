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
				ComplexMarkerResponse::complexId,
				ComplexMarkerResponse::lat,
				ComplexMarkerResponse::lng,
				ComplexMarkerResponse::latestDealAmount,
				ComplexMarkerResponse::unitCntSum
			)
			.containsExactly(tuple(1001L, null, 37.5123, 127.0456, 125000L, 860L));
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

	@Test
	@DisplayName("재건축 필지 마커는 현재 세대 complex 기준 좌표와 거래값을 반환한다")
	void redevelopmentMarkerUsesCurrentGenerationComplexCoordinateAndTrade() {
		seedRedevelopmentParcel();
		JdbcMapMarkerRepository repository = new JdbcMapMarkerRepository(jdbcClient);

		assertThat(repository.findComplexMarkers(request(null, null)))
			.extracting(
				ComplexMarkerResponse::parcelId,
				ComplexMarkerResponse::complexId,
				ComplexMarkerResponse::lat,
				ComplexMarkerResponse::lng,
				ComplexMarkerResponse::latestDealAmount,
				ComplexMarkerResponse::unitCntSum
			)
			.containsExactly(tuple(2001L, 802L, 37.6010, 127.1010, 190000L, 900L));
	}

	@Test
	@DisplayName("동시 존재 단지가 building 좌표로 확정되면 complex별 marker를 반환한다")
	void concurrentComplexesWithBuildingCoordinatesReturnComplexLevelMarkers() {
		seedConcurrentComplexMarkers();
		JdbcMapMarkerRepository repository = new JdbcMapMarkerRepository(jdbcClient);

		assertThat(repository.findComplexMarkers(request(null, null)))
			.extracting(
				ComplexMarkerResponse::parcelId,
				ComplexMarkerResponse::complexId,
				ComplexMarkerResponse::lat,
				ComplexMarkerResponse::lng,
				ComplexMarkerResponse::latestDealAmount,
				ComplexMarkerResponse::unitCntSum
			)
			.containsExactly(
				tuple(3001L, 901L, 37.6110, 127.1110, 150000L, 410L),
				tuple(3001L, 902L, 37.6120, 127.1120, 220000L, 520L)
			);
	}

	@Test
	@DisplayName("3세대+ 순차 재건축 마커는 현재 세대 단위수만 반영하고 철거 세대를 합산하지 않는다")
	void multiGenerationRedevelopmentMarkerMustNotSumDemolishedUnits() {
		seedMultiGenerationRedevelopmentParcel();
		JdbcMapMarkerRepository repository = new JdbcMapMarkerRepository(jdbcClient);

		assertThat(repository.findComplexMarkers(request(null, null)))
			.extracting(ComplexMarkerResponse::parcelId, ComplexMarkerResponse::unitCntSum)
			.containsExactly(tuple(4001L, 900L));
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

	private void seedRedevelopmentParcel() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (2001, 1, '1168010300101400009', 'Redeveloped lot', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (801, 2001, 'COMPLEX-PK-801', 'APT-801', 'Old Mansion', 500, DATE '1985-01-01'),
			    (802, 2001, 'COMPLEX-PK-802', 'APT-802', 'New Tower', 900, DATE '2022-06-01')
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (2001, '1168010300101400009', 'SKIPPED', 'REDEVELOPED', 'HIGH')
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
			    (801, 37.5990, 127.0990, 'PARCEL_FALLBACK', 40, 'old fallback'),
			    (802, 37.6010, 127.1010, 'BUILDING_FOOTPRINT', 90, 'new representative footprint')
			""").update();
		Long rawId = insertRawIngest("rtms-redev");
		insertTrade(rawId, 801L, LocalDate.of(2016, 1, 1), 80000L, "rtms-redev-801-1", "COMPLEX-PK-801");
		insertTrade(rawId, 801L, LocalDate.of(2026, 1, 1), 91000L, "rtms-redev-801-2", "COMPLEX-PK-801");
		insertTrade(rawId, 802L, LocalDate.of(2023, 1, 1), 180000L, "rtms-redev-802-1", "COMPLEX-PK-802");
		insertTrade(rawId, 802L, LocalDate.of(2025, 1, 1), 190000L, "rtms-redev-802-2", "COMPLEX-PK-802");
	}

	private void seedConcurrentComplexMarkers() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (3001, 1, '1168010300101400010', 'Concurrent lot', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (901, 3001, 'COMPLEX-PK-901', 'APT-901', 'Concurrent A', 410, DATE '2018-01-01'),
			    (902, 3001, 'COMPLEX-PK-902', 'APT-902', 'Concurrent B', 520, DATE '2020-01-01')
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (3001, '1168010300101400010', 'RESOLVED', 'CONCURRENT', 'HIGH')
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
			    (901, 37.6110, 127.1110, 'BUILDING_FOOTPRINT', 90, 'tower A footprint'),
			    (902, 37.6120, 127.1120, 'BUILDING_FOOTPRINT', 90, 'tower B footprint')
			""").update();
		Long rawId = insertRawIngest("rtms-concurrent");
		insertTrade(rawId, 901L, LocalDate.of(2025, 1, 1), 150000L, "rtms-concurrent-901", "COMPLEX-PK-901");
		insertTrade(rawId, 902L, LocalDate.of(2025, 2, 1), 220000L, "rtms-concurrent-902", "COMPLEX-PK-902");
	}

	private void seedMultiGenerationRedevelopmentParcel() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (4001, 1, '1168010300101400011', 'Multi-generation redevelopment lot', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt, use_date)
			VALUES
			    (1101, 4001, 'COMPLEX-PK-1101', 'APT-1101', 'Gen1 demolished', 300, DATE '1985-01-01'),
			    (1102, 4001, 'COMPLEX-PK-1102', 'APT-1102', 'Gen2 demolished', 500, DATE '2000-01-01'),
			    (1103, 4001, 'COMPLEX-PK-1103', 'APT-1103', 'Gen3 current', 900, DATE '2020-01-01')
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (4001, '1168010300101400011', 'SKIPPED', 'REDEVELOPED', 'HIGH')
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
			    (1101, 37.5101, 127.0101, 'PARCEL_FALLBACK', 40, 'gen1 fallback'),
			    (1102, 37.5102, 127.0102, 'PARCEL_FALLBACK', 40, 'gen2 fallback'),
			    (1103, 37.5103, 127.0103, 'PARCEL_FALLBACK', 40, 'gen3 fallback')
			""").update();
		Long rawId = insertRawIngest("rtms-multigen");
		insertTrade(rawId, 1101L, LocalDate.of(2010, 1, 1), 60000L, "rtms-multigen-1101-1", "COMPLEX-PK-1101");
		insertTrade(rawId, 1101L, LocalDate.of(2012, 1, 1), 65000L, "rtms-multigen-1101-2", "COMPLEX-PK-1101");
		insertTrade(rawId, 1102L, LocalDate.of(2014, 1, 1), 90000L, "rtms-multigen-1102-1", "COMPLEX-PK-1102");
		insertTrade(rawId, 1102L, LocalDate.of(2016, 1, 1), 95000L, "rtms-multigen-1102-2", "COMPLEX-PK-1102");
		insertTrade(rawId, 1103L, LocalDate.of(2022, 1, 1), 180000L, "rtms-multigen-1103-1", "COMPLEX-PK-1103");
		insertTrade(rawId, 1103L, LocalDate.of(2025, 1, 1), 190000L, "rtms-multigen-1103-2", "COMPLEX-PK-1103");
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
