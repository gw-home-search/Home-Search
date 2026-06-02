package com.home.infrastructure.persistence.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcPropertyReadRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("search/region/detail/trade read API는 baseline core table로 backing된다")
	void readsPropertyMapExplorationDataFromBaselineTables() {
		seedPropertyExplorationData();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("sample"))
			.singleElement()
			.satisfies(result -> {
				assertThat(result.complexId()).isEqualTo(501L);
				assertThat(result.complexName()).isEqualTo("Sample trade name");
				assertThat(result.parcelId()).isEqualTo(1001L);
				assertThat(result.latitude()).isEqualTo(37.5123);
				assertThat(result.longitude()).isEqualTo(127.0456);
				assertThat(result.address()).isEqualTo("Sample address");
			});

		assertThat(repository.findRootRegions())
			.singleElement()
			.satisfies(region -> {
				assertThat(region.id()).isEqualTo(1L);
				assertThat(region.name()).isEqualTo("Seoul");
			});

		assertThat(repository.findRegionDetail(1L))
			.hasValueSatisfying(region -> {
				assertThat(region.latitude()).isEqualTo(37.5663);
				assertThat(region.longitude()).isEqualTo(126.9780);
				assertThat(region.children())
					.extracting("name")
					.containsExactly("Gangnam-gu");
			});

		assertThat(repository.findParcelDetail(1001L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.parcelId()).isEqualTo(1001L);
				assertThat(detail.name()).isEqualTo("Sample Apartment");
				assertThat(detail.tradeName()).isEqualTo("Sample trade name");
				assertThat(detail.unitCnt()).isEqualTo(740);
				assertThat(detail.platArea()).isEqualByComparingTo(new BigDecimal("12345.67"));
				assertThat(detail.useDate()).isEqualTo(LocalDate.of(2015, 3, 20));
			});

		assertThat(repository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> {
				assertThat(tradeList.parcelId()).isEqualTo(1001L);
				assertThat(tradeList.trades())
					.extracting("tradeId")
					.containsExactly(9002L, 9001L);
				assertThat(tradeList.trades().get(0).dealAmount()).isEqualTo(130000L);
			});
	}

	@Test
	@DisplayName("trade read API는 parcel에 complex가 있지만 trade가 없으면 empty trade list를 반환한다")
	void tradeListReturnsEmptyWhenParcelAndComplexExistWithoutTrades() {
		seedComplex();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> {
				assertThat(tradeList.parcelId()).isEqualTo(1001L);
				assertThat(tradeList.trades()).isEmpty();
			});
	}

	@Test
	@DisplayName("trade read API는 canceled trade를 목록에서 제외한다")
	void tradeListExcludesCanceledTrade() {
		seedPropertyExplorationData();
		jdbcClient.sql("""
			UPDATE trade
			SET deleted_at = now()
			WHERE id = 9002
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> assertThat(tradeList.trades())
				.extracting("tradeId")
				.containsExactly(9001L));
	}

	@Test
	@DisplayName("detail/trade read API는 complexId가 있으면 같은 parcel의 선택 complex로 범위를 좁힌다")
	void detailAndTradeCanBeScopedToSelectedComplex() {
		seedTwoComplexParcel();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.findParcelDetail(2001L, 702L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.parcelId()).isEqualTo(2001L);
				assertThat(detail.complexId()).isEqualTo(702L);
				assertThat(detail.name()).isEqualTo("Complex B");
				assertThat(detail.unitCnt()).isEqualTo(320);
			});
		assertThat(repository.findTradeList(2001L, 702L))
			.hasValueSatisfying(tradeList -> {
				assertThat(tradeList.parcelId()).isEqualTo(2001L);
				assertThat(tradeList.complexId()).isEqualTo(702L);
				assertThat(tradeList.trades())
					.extracting("tradeId")
					.containsExactly(9702L);
			});
		assertThat(repository.findParcelDetail(2001L, 999L)).isEmpty();
		assertThat(repository.findTradeList(2001L, 999L)).isEmpty();
	}

	@Test
	@DisplayName("trade read API는 동일 조건이어도 aptDong이 다른 거래를 모두 반환한다")
	void tradeListKeepsSameConditionTradesWhenAptDongDiffers() {
		seedComplex();
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
			VALUES
			    (90001, 'RTMS', 'same-condition-101', '11680', '202512', 1, '{}', 'hash-101', 'NORMALIZED', now()),
			    (90002, 'RTMS', 'same-condition-102', '11680', '202512', 1, '{}', 'hash-102', 'NORMALIZED', now())
			""").update();
		jdbcClient.sql("""
			INSERT INTO trade (
			    id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES
			    (9001, 501, DATE '2025-12-01', 125000, 12, 84.93, '101', 'RTMS', 'same-condition-101', 'COMPLEX-PK-501', 'APT-501', 90001),
			    (9002, 501, DATE '2025-12-01', 125000, 12, 84.93, '102', 'RTMS', 'same-condition-102', 'COMPLEX-PK-501', 'APT-501', 90002)
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> assertThat(tradeList.trades())
				.extracting("tradeId", "aptDong")
				.containsExactly(
					tuple(9002L, "102"),
					tuple(9001L, "101")
				));
	}

	@Test
	@DisplayName("search API complexName은 레거시처럼 trade_name을 name보다 우선한다")
	void searchComplexesUsesLegacyDisplayNamePolicy() {
		seedComplex();
		jdbcClient.sql("""
			UPDATE complex
			SET name = 'Building Register Name',
			    trade_name = 'Legacy Trade Display Name'
			WHERE id = 501
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("legacy"))
			.singleElement()
			.satisfies(result -> assertThat(result.complexName()).isEqualTo("Legacy Trade Display Name"));
	}

	@Test
	@DisplayName("search API complexName은 trade_name이 blank이면 name으로 fallback한다")
	void searchComplexesFallsBackToNameWhenTradeNameIsBlank() {
		seedComplex();
		jdbcClient.sql("""
			UPDATE complex
			SET name = 'Building Register Name',
			    trade_name = '   '
			WHERE id = 501
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("building"))
			.singleElement()
			.satisfies(result -> assertThat(result.complexName()).isEqualTo("Building Register Name"));
	}

	@Test
	@DisplayName("search API는 보존된 complex alias도 검색 evidence로 사용한다")
	void searchComplexesFindsComplexByPreservedAlias() {
		seedComplex();
		jdbcClient.sql("""
			UPDATE complex
			SET name = 'Building Register Name',
			    trade_name = 'Official Trade Name'
			WHERE id = 501
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_name_alias (
			    complex_id,
			    alias_type,
			    alias_name,
			    normalized_name,
			    source
			)
			VALUES (
			    501,
			    'RTMS_APT_NAME',
			    'RTMS Wobbly Name',
			    'rtmswobblyname',
			    'RTMS'
			)
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("wobbly"))
			.singleElement()
			.satisfies(result -> {
				assertThat(result.complexId()).isEqualTo(501L);
				assertThat(result.complexName()).isEqualTo("Official Trade Name");
				assertThat(result.parcelId()).isEqualTo(1001L);
			});
		assertThat(repository.searchComplexes("rtmswobbly"))
			.singleElement()
			.satisfies(result -> assertThat(result.complexId()).isEqualTo(501L));
	}

	@Test
	@DisplayName("search API alias substring 검색은 pg_trgm GIN index 기반을 가진다")
	void complexAliasSubstringSearchHasTrigramIndexes() {
		assertThat(extensionExists("pg_trgm")).isTrue();
		assertThat(indexDefinition("ix_complex_name_alias_normalized_name_trgm"))
			.contains("USING gin")
			.contains("normalized_name gin_trgm_ops");
		assertThat(indexDefinition("ix_complex_name_alias_alias_name_lower_trgm"))
			.contains("USING gin")
			.contains("lower((alias_name)::text) gin_trgm_ops");
	}

	@Test
	@DisplayName("search API primary substring 검색은 complex와 parcel trigram index 기반을 가진다")
	void searchComplexesPrimarySubstringSearchHasTrigramIndexes() {
		assertThat(extensionExists("pg_trgm")).isTrue();
		assertThat(indexDefinition("ix_complex_name_lower_trgm"))
			.contains("USING gin")
			.contains("lower")
			.contains("name")
			.contains("gin_trgm_ops");
		assertThat(indexDefinition("ix_complex_trade_name_lower_trgm"))
			.contains("USING gin")
			.contains("lower")
			.contains("trade_name")
			.contains("gin_trgm_ops");
		assertThat(indexDefinition("ix_parcel_address_lower_trgm"))
			.contains("USING gin")
			.contains("lower")
			.contains("address")
			.contains("gin_trgm_ops");
	}

	@Test
	@DisplayName("search API는 complex 단위 결과라 같은 parcelId를 여러 결과에서 반환할 수 있다")
	void searchComplexesCanReturnMultipleComplexesForSameParcel() {
		seedComplex();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Sample River Tower', 'Sample River Trade', 120)
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("sample"))
			.extracting("complexId", "parcelId")
			.containsExactly(
				tuple(501L, 1001L),
				tuple(502L, 1001L)
			);
	}

	@Test
	@DisplayName("search/detail read API는 complex 표시 좌표가 있으면 parcel 좌표보다 우선한다")
	void searchAndDetailPreferComplexDisplayCoordinate() {
		seedPropertyExplorationData();
		jdbcClient.sql("""
			INSERT INTO complex_display_coordinate (
			    complex_id,
			    latitude,
			    longitude,
			    coordinate_source,
			    confidence,
			    reason
			)
			VALUES (501, 37.6010, 127.1010, 'PARCEL_FALLBACK', 70, 'test display coordinate')
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("sample"))
			.singleElement()
			.satisfies(result -> {
				assertThat(result.latitude()).isEqualTo(37.6010);
				assertThat(result.longitude()).isEqualTo(127.1010);
			});
		assertThat(repository.findParcelDetail(1001L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.latitude()).isEqualTo(37.6010);
				assertThat(detail.longitude()).isEqualTo(127.1010);
			});
	}

	@Test
	@DisplayName("detail은 parcel 대표 complex를 반환하고 trade는 parcel 하위 모든 complex 거래를 반환한다")
	void detailUsesRepresentativeComplexAndTradeListIncludesAllParcelComplexTrades() {
		seedPropertyExplorationData();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Secondary Complex', 'Secondary Trade Name', 120)
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
			VALUES (90003, 'RTMS', 'sample-rtms-20251220', '11680', '202512', 1, '{}', 'hash-3', 'NORMALIZED', now())
			""").update();
		jdbcClient.sql("""
			INSERT INTO trade (
			    id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES (
			    9003,
			    502,
			    DATE '2025-12-20',
			    150000,
			    20,
			    114.93,
			    '201',
			    'RTMS',
			    'sample-rtms-20251220',
			    'COMPLEX-PK-502',
			    'APT-502',
			    90003
			)
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.findParcelDetail(1001L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.name()).isEqualTo("Sample Apartment");
				assertThat(detail.tradeName()).isEqualTo("Sample trade name");
			});
		assertThat(repository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> assertThat(tradeList.trades())
				.extracting("tradeId")
				.containsExactly(9003L, 9002L, 9001L));
	}

	@Test
	@DisplayName("detail은 재건축 필지에서 철거된 구단지 대신 생존(신축) 단지를 대표로 반환한다")
	void detailPrefersSurvivingComplexForRedevelopedParcel() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400009', 'Redeveloped lot', 37.5123, 127.0456)
			""").update();
		// 구단지가 더 낮은 c.id (501) → 현행 ORDER BY c.id LIMIT 1이면 철거 단지가 대표가 됨.
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt, use_date)
			VALUES
			    (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Old Mansion', NULL, 500, DATE '1985-01-01'),
			    (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'New Tower', 'New Tower Trade', 900, DATE '2022-06-01')
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_coordinate_case (parcel_id, pnu, status, relation_type, relation_confidence)
			VALUES (1001, '1168010300101400009', 'SKIPPED', 'REDEVELOPED', 'HIGH')
			""").update();
		jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id, source, source_key, lawd_cd, deal_ymd, page_no, payload, payload_hash, status, processed_at
			)
			VALUES (90100, 'RTMS', 'redev-detail', '11680', '202501', 1, '{}', 'hash-redev-detail', 'NORMALIZED', now())
			""").update();
		jdbcClient.sql("""
			INSERT INTO trade (
			    id, complex_id, deal_date, deal_amount, floor, excl_area, apt_dong,
			    source, source_key, complex_pk, apt_seq, raw_ingest_id
			)
			VALUES
			    (9101, 501, DATE '2016-01-01', 80000, 5, 84.93, '101', 'RTMS', 'redev-d-501-1', 'COMPLEX-PK-501', 'APT-501', 90100),
			    (9102, 501, DATE '2018-01-01', 90000, 6, 84.93, '101', 'RTMS', 'redev-d-501-2', 'COMPLEX-PK-501', 'APT-501', 90100),
			    (9103, 502, DATE '2023-01-01', 180000, 15, 84.93, '101', 'RTMS', 'redev-d-502-1', 'COMPLEX-PK-502', 'APT-502', 90100),
			    (9104, 502, DATE '2025-01-01', 190000, 16, 84.93, '101', 'RTMS', 'redev-d-502-2', 'COMPLEX-PK-502', 'APT-502', 90100)
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.findParcelDetail(1001L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.name()).isEqualTo("New Tower");
				assertThat(detail.tradeName()).isEqualTo("New Tower Trade");
				assertThat(detail.unitCnt()).isEqualTo(900);
				assertThat(detail.useDate()).isEqualTo(LocalDate.of(2022, 6, 1));
			});
	}

	@Test
	@DisplayName("detail/trade read API는 parcel 또는 complex parent path가 없으면 empty가 된다")
	void missingParentPathReturnsEmpty() {
		seedPropertyExplorationData();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.findParcelDetail(404L)).isEmpty();
		assertThat(repository.findTradeList(404L)).isEmpty();
	}

	private boolean extensionExists(String extensionName) {
		return Boolean.TRUE.equals(jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM pg_extension
			    WHERE extname = :extensionName
			)
			""")
			.param("extensionName", extensionName)
			.query(Boolean.class)
			.single());
	}

	private String indexDefinition(String indexName) {
		return jdbcClient.sql("""
			SELECT indexdef
			FROM pg_indexes
			WHERE schemaname = 'public'
			  AND indexname = :indexName
			""")
			.param("indexName", indexName)
			.query(String.class)
			.optional()
			.orElse("");
	}

	private void seedTwoComplexParcel() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (2001, 1, '1168010300101400099', 'Two complex address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES
			    (701, 2001, 'COMPLEX-PK-701', 'APT-701', 'Complex A', 'Complex A trade', 210),
			    (702, 2001, 'COMPLEX-PK-702', 'APT-702', 'Complex B', 'Complex B trade', 320)
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
			VALUES
			    (9701, 'RTMS', 'scoped-701', '11680', '202512', 1, '{}', 'hash-scoped-701', 'NORMALIZED', now()),
			    (9702, 'RTMS', 'scoped-702', '11680', '202512', 1, '{}', 'hash-scoped-702', 'NORMALIZED', now())
			""").update();
		jdbcClient.sql("""
			INSERT INTO trade (
			    id,
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES
			    (9701, 701, DATE '2025-12-01', 125000, 12, 84.93, '101', 'RTMS', 'scoped-701', 'COMPLEX-PK-701', 'APT-701', 9701),
			    (9702, 702, DATE '2025-12-15', 130000, 15, 59.93, '201', 'RTMS', 'scoped-702', 'COMPLEX-PK-702', 'APT-702', 9702)
			""").update();
	}
}
