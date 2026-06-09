package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import com.home.application.coordinate.lookup.ParcelCoordinate;
import com.home.application.ingest.trade.OpenApiTradeItem;
import com.home.infrastructure.persistence.ingest.matching.JdbcComplexMasterBootstrapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcComplexMasterBootstrapperTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("RTMS bootstrap은 외부 metadata resolver 없이 identity-only complex를 PENDING으로 만든다")
	void bootstrapsIdentityOnlyComplexWithoutMetadataResolver() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		JdbcComplexMasterBootstrapper bootstrapper = new JdbcComplexMasterBootstrapper(
			jdbcClient,
			pnu -> expectedPnu().equals(pnu) ? Optional.of(new ParcelCoordinate(
				new BigDecimal("37.5012345"),
				new BigDecimal("127.0543210")
			)) : Optional.empty()
		);

		var result = bootstrapper.bootstrap(rtmsItem("APT-LIVE-501", "Live Sample Apartment", "777-1"));

		assertThat(result.attempted()).isTrue();
		assertThat(result.changed()).isTrue();
		assertThat(complexBootstrapRow("APT-LIVE-501"))
			.containsEntry("complex_pk", "RTMS:APT-LIVE-501")
			.containsEntry("name", "Live Sample Apartment")
			.containsEntry("trade_name", "Live Sample Apartment")
			.containsEntry("metadata_status", "PENDING")
			.containsEntry("dong_cnt", null)
			.containsEntry("unit_cnt", null);
		assertThat(complexAliasCount("APT-LIVE-501", "RTMS_APT_NAME", "livesampleapartment")).isEqualTo(1);
	}

	@Test
	@DisplayName("RTMS bootstrap은 신규 complex를 identity-only로 만들고 metadata는 PENDING으로 남긴다")
	void bootstrapsNewComplexWithPendingMetadata() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		JdbcComplexMasterBootstrapper bootstrapper = new JdbcComplexMasterBootstrapper(
			jdbcClient,
			pnu -> expectedPnu().equals(pnu) ? Optional.of(new ParcelCoordinate(
				new BigDecimal("37.5012345"),
				new BigDecimal("127.0543210")
			)) : Optional.empty()
		);

		var result = bootstrapper.bootstrap(rtmsItem("APT-LIVE-501", "Live Sample Apartment", "777-1"));

		assertThat(result.attempted()).isTrue();
		assertThat(result.changed()).isTrue();
		assertThat(complexMetadata("APT-LIVE-501"))
			.containsEntry("metadata_status", "PENDING")
			.containsEntry("dong_cnt", null)
			.containsEntry("unit_cnt", null)
			.containsEntry("plat_area", null)
			.containsEntry("arch_area", null)
			.containsEntry("tot_area", null)
			.containsEntry("bc_rat", null)
			.containsEntry("vl_rat", null)
			.containsEntry("use_date", null);
	}

	@Test
	@DisplayName("RTMS bootstrap은 숫자 지번 PNU를 만들 수 없을 때 opt-in identity resolver PNU로 complex를 만든다")
	void bootstrapsWithIdentityResolverPnuWhenJibunCannotDerivePnu() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1153011200', 'Block-dong', 'eup-myeon-dong')
			""").update();
		String fallbackPnu = "1153011200102380000";
		JdbcComplexMasterBootstrapper bootstrapper = new JdbcComplexMasterBootstrapper(
			jdbcClient,
			pnu -> fallbackPnu.equals(pnu) ? Optional.of(new ParcelCoordinate(
				new BigDecimal("37.5012345"),
				new BigDecimal("127.0543210")
			)) : Optional.empty(),
			item -> Optional.of(fallbackPnu)
		);

		var result = bootstrapper.bootstrap(rtmsItem("11530-4350", "하버라인4단지", "가-238"));

		assertThat(result.attempted()).isTrue();
		assertThat(result.changed()).isTrue();
		assertThat(complexBootstrapRow("11530-4350"))
			.containsEntry("complex_pk", "RTMS:11530-4350")
			.containsEntry("name", "하버라인4단지")
			.containsEntry("metadata_status", "PENDING");
		assertThat(complexParcelPnu("RTMS:11530-4350")).isEqualTo(fallbackPnu);
	}

	@Test
	@DisplayName("RTMS bootstrap은 region 8자리 법정동 코드 fallback으로 parcel address를 저장한다")
	void bootstrapsParcelAddressFromRegionFallbackCodeAndPnuLotNumber() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '11680103', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		JdbcComplexMasterBootstrapper bootstrapper = new JdbcComplexMasterBootstrapper(
			jdbcClient,
			pnu -> expectedPnu().equals(pnu) ? Optional.of(new ParcelCoordinate(
				new BigDecimal("37.5012345"),
				new BigDecimal("127.0543210")
			)) : Optional.empty()
		);

		var result = bootstrapper.bootstrap(rtmsItem("APT-LIVE-501", "Live Sample Apartment", "777-1"));

		assertThat(result.changed()).isTrue();
		assertThat(parcelRow(expectedPnu()))
			.containsEntry("region_id", 1L)
			.containsEntry("address", "Sample-dong 777-1");
	}

	@Test
	@DisplayName("RTMS bootstrap은 좌표가 없어도 PNU identity parcel shell과 complex를 만든다")
	void bootstrapsCoordinatePendingParcelShellWhenCoordinateIsUnavailable() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		JdbcComplexMasterBootstrapper bootstrapper = new JdbcComplexMasterBootstrapper(
			jdbcClient,
			pnu -> Optional.empty()
		);

		var result = bootstrapper.bootstrap(rtmsItem("APT-LIVE-404", "Missing Coordinate Apartment", "888-1"));

		assertThat(result.attempted()).isTrue();
		assertThat(result.changed()).isTrue();
		assertThat(parcelRow("1168010300108880001"))
			.containsEntry("region_id", 1L)
			.containsEntry("address", "Sample-dong 888-1")
			.containsEntry("latitude", null)
			.containsEntry("longitude", null);
		assertThat(complexBootstrapRow("APT-LIVE-404"))
			.containsEntry("complex_pk", "RTMS:APT-LIVE-404")
			.containsEntry("name", "Missing Coordinate Apartment")
			.containsEntry("metadata_status", "PENDING");
	}

	@Test
	@DisplayName("RTMS bootstrap은 이미 존재하는 complex의 metadata를 동기 보강하지 않고 alias만 갱신한다")
	void leavesExistingComplexMetadataPendingAndUpsertsAlias() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400001', 'Sample address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name)
			VALUES (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Sample Apartment', 'Sample Apartment')
			""").update();
		JdbcComplexMasterBootstrapper bootstrapper = new JdbcComplexMasterBootstrapper(
			jdbcClient,
			pnu -> Optional.empty()
		);

		var result = bootstrapper.bootstrap(rtmsItem("APT-501", "Sample Apartment", "140-1"));

		assertThat(result.attempted()).isTrue();
		assertThat(result.changed()).isFalse();
		assertThat(complexMetadata("APT-501"))
			.containsEntry("metadata_status", "PENDING")
			.containsEntry("dong_cnt", null)
			.containsEntry("unit_cnt", null)
			.containsEntry("plat_area", null)
			.containsEntry("arch_area", null)
			.containsEntry("tot_area", null)
			.containsEntry("bc_rat", null)
			.containsEntry("vl_rat", null)
			.containsEntry("use_date", null);
		assertThat(complexAliasCount("APT-501", "RTMS_APT_NAME", "sampleapartment")).isEqualTo(1);
	}

	@Test
	@DisplayName("RTMS bootstrap은 complex_pk 충돌 시 기존 complex의 parcel_id를 재할당하지 않는다")
	void doesNotReassignExistingComplexPkToDifferentParcel() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400001', 'Original address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name)
			VALUES (501, 1001, 'RTMS:APT-501', NULL, 'Original Apartment', 'Original Apartment')
			""").update();
		JdbcComplexMasterBootstrapper bootstrapper = new JdbcComplexMasterBootstrapper(
			jdbcClient,
			pnu -> Optional.of(new ParcelCoordinate(new BigDecimal("37.5012345"), new BigDecimal("127.0543210")))
		);

		var result = bootstrapper.bootstrap(rtmsItem("APT-501", "Moved Apartment", "777-1"));

		assertThat(result.attempted()).isTrue();
		assertThat(result.failureReason()).contains("complex_pk parcel pnu conflict");
		assertThat(complexParcelPnu("RTMS:APT-501")).isEqualTo("1168010300101400001");
		assertThat(parcelCount("1168010300107770001")).isZero();
		assertThat(complexAliasCount("APT-501", "RTMS_APT_NAME", "movedapartment")).isZero();
	}

	private OpenApiTradeItem rtmsItem(String aptSeq, String aptName, String jibun) {
		return new OpenApiTradeItem(
			"101",
			aptName,
			aptSeq,
			"125,000",
			1,
			12,
			2025,
			84.93,
			12,
			jibun,
			"11680",
			"10300",
			"{\"aptSeq\":\"%s\",\"aptNm\":\"%s\",\"jibun\":\"%s\"}"
				.formatted(aptSeq, aptName, jibun)
		);
	}

	private String expectedPnu() {
		return "1168010300107770001";
	}

	private java.util.Map<String, Object> complexMetadata(String aptSeq) {
		return jdbcClient.sql("""
			SELECT metadata_status, dong_cnt, unit_cnt, plat_area, arch_area, tot_area, bc_rat, vl_rat, use_date
			FROM complex
			WHERE apt_seq = :aptSeq
			""")
			.param("aptSeq", aptSeq)
			.query((resultSet, rowNumber) -> {
				java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("metadata_status", resultSet.getString("metadata_status"));
				row.put("dong_cnt", resultSet.getObject("dong_cnt"));
				row.put("unit_cnt", resultSet.getObject("unit_cnt"));
				row.put("plat_area", resultSet.getBigDecimal("plat_area"));
				row.put("arch_area", resultSet.getBigDecimal("arch_area"));
				row.put("tot_area", resultSet.getBigDecimal("tot_area"));
				row.put("bc_rat", resultSet.getBigDecimal("bc_rat"));
				row.put("vl_rat", resultSet.getBigDecimal("vl_rat"));
				row.put("use_date", resultSet.getObject("use_date", LocalDate.class));
				return row;
			})
			.single();
	}

	private java.util.Map<String, Object> complexBootstrapRow(String aptSeq) {
		return jdbcClient.sql("""
			SELECT complex_pk, name, trade_name, metadata_status, dong_cnt, unit_cnt
			FROM complex
			WHERE apt_seq = :aptSeq
			""")
			.param("aptSeq", aptSeq)
			.query((resultSet, rowNumber) -> {
				java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("complex_pk", resultSet.getString("complex_pk"));
				row.put("name", resultSet.getString("name"));
				row.put("trade_name", resultSet.getString("trade_name"));
				row.put("metadata_status", resultSet.getString("metadata_status"));
				row.put("dong_cnt", resultSet.getObject("dong_cnt"));
				row.put("unit_cnt", resultSet.getObject("unit_cnt"));
				return row;
			})
			.single();
	}

	private long complexAliasCount(String aptSeq, String aliasType, String normalizedName) {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM complex c
			JOIN complex_name_alias a ON a.complex_id = c.id
			WHERE c.apt_seq = :aptSeq
			  AND a.alias_type = :aliasType
			  AND a.normalized_name = :normalizedName
			""")
			.param("aptSeq", aptSeq)
			.param("aliasType", aliasType)
			.param("normalizedName", normalizedName)
			.query(Long.class)
			.single();
	}

	private String complexParcelPnu(String complexPk) {
		return jdbcClient.sql("""
			SELECT p.pnu
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			WHERE c.complex_pk = :complexPk
			""")
			.param("complexPk", complexPk)
			.query(String.class)
			.single();
	}

	private long parcelCount(String pnu) {
		return jdbcClient.sql("""
			SELECT count(*)
			FROM parcel
			WHERE pnu = :pnu
			""")
			.param("pnu", pnu)
			.query(Long.class)
			.single();
	}

	private java.util.Map<String, Object> parcelRow(String pnu) {
		return jdbcClient.sql("""
			SELECT region_id, address, latitude, longitude
			FROM parcel
			WHERE pnu = :pnu
			""")
			.param("pnu", pnu)
			.query((resultSet, rowNumber) -> {
				java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("region_id", resultSet.getObject("region_id", Long.class));
				row.put("address", resultSet.getString("address"));
				row.put("latitude", resultSet.getBigDecimal("latitude"));
				row.put("longitude", resultSet.getBigDecimal("longitude"));
				return row;
			})
			.single();
	}
}
