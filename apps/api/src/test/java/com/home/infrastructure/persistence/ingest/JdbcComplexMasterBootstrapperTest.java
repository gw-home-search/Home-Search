package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import com.home.application.ingest.ComplexMetadata;
import com.home.application.ingest.ComplexMetadataResolver;
import com.home.application.ingest.OpenApiTradeItem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcComplexMasterBootstrapperTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("RTMS bootstrap은 resolver metadata를 새 complex insert에 함께 저장한다")
	void bootstrapsNewComplexWithResolverMetadata() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		JdbcComplexMasterBootstrapper bootstrapper = new JdbcComplexMasterBootstrapper(
			jdbcClient,
			(pnu, item) -> expectedPnu().equals(pnu) ? Optional.of(new ParcelCoordinate(
				new BigDecimal("37.5012345"),
				new BigDecimal("127.0543210")
			)) : Optional.empty(),
			metadataResolver()
		);

		var result = bootstrapper.bootstrap(rtmsItem("APT-LIVE-501", "Live Sample Apartment", "777-1"));

		assertThat(result.attempted()).isTrue();
		assertThat(result.changed()).isTrue();
		assertThat(complexMetadata("APT-LIVE-501"))
			.containsEntry("dong_cnt", 8)
			.containsEntry("unit_cnt", 740)
			.containsEntry("plat_area", new BigDecimal("12345.67"))
			.containsEntry("arch_area", new BigDecimal("2345.67"))
			.containsEntry("tot_area", new BigDecimal("98765.43"))
			.containsEntry("bc_rat", new BigDecimal("22.50"))
			.containsEntry("vl_rat", new BigDecimal("199.80"))
			.containsEntry("use_date", LocalDate.of(2015, 3, 20));
	}

	@Test
	@DisplayName("RTMS bootstrap은 이미 존재하는 complex의 비어 있는 metadata도 보강한다")
	void enrichesExistingComplexMetadata() {
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
			(pnu, item) -> Optional.empty(),
			metadataResolver()
		);

		var result = bootstrapper.bootstrap(rtmsItem("APT-501", "Sample Apartment", "140-1"));

		assertThat(result.attempted()).isTrue();
		assertThat(result.changed()).isFalse();
		assertThat(complexMetadata("APT-501"))
			.containsEntry("dong_cnt", 8)
			.containsEntry("unit_cnt", 740)
			.containsEntry("plat_area", new BigDecimal("12345.67"))
			.containsEntry("arch_area", new BigDecimal("2345.67"))
			.containsEntry("tot_area", new BigDecimal("98765.43"))
			.containsEntry("bc_rat", new BigDecimal("22.50"))
			.containsEntry("vl_rat", new BigDecimal("199.80"))
			.containsEntry("use_date", LocalDate.of(2015, 3, 20));
	}

	private ComplexMetadataResolver metadataResolver() {
		return (item, pnu, parcelAddress) -> Optional.of(new ComplexMetadata(
			8,
			740,
			new BigDecimal("12345.67"),
			new BigDecimal("2345.67"),
			new BigDecimal("98765.43"),
			new BigDecimal("22.50"),
			new BigDecimal("199.80"),
			LocalDate.of(2015, 3, 20)
		));
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
			SELECT dong_cnt, unit_cnt, plat_area, arch_area, tot_area, bc_rat, vl_rat, use_date
			FROM complex
			WHERE apt_seq = :aptSeq
			""")
			.param("aptSeq", aptSeq)
			.query((resultSet, rowNumber) -> java.util.Map.<String, Object>of(
				"dong_cnt", resultSet.getObject("dong_cnt"),
				"unit_cnt", resultSet.getObject("unit_cnt"),
				"plat_area", resultSet.getBigDecimal("plat_area"),
				"arch_area", resultSet.getBigDecimal("arch_area"),
				"tot_area", resultSet.getBigDecimal("tot_area"),
				"bc_rat", resultSet.getBigDecimal("bc_rat"),
				"vl_rat", resultSet.getBigDecimal("vl_rat"),
				"use_date", resultSet.getObject("use_date", LocalDate.class)
			))
			.single();
	}
}
