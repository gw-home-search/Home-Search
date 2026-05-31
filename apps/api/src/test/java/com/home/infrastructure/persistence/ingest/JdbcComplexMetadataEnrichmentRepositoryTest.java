package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.home.application.ingest.ComplexMetadata;
import com.home.application.ingest.ComplexMetadataResolution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcComplexMetadataEnrichmentRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("metadata enrichment repository는 PENDING complex를 조회하고 NULL metadata만 보강한다")
	void findsPendingComplexesAndAppliesMetadataWithCoalesce() {
		seedPendingComplex();
		JdbcComplexMetadataEnrichmentRepository repository = new JdbcComplexMetadataEnrichmentRepository(jdbcClient);

		assertThat(repository.findPending(10)).singleElement()
			.satisfies(lookup -> {
				assertThat(lookup.complexId()).isEqualTo(501L);
				assertThat(lookup.aptSeq()).isEqualTo("APT-501");
				assertThat(lookup.aptName()).isEqualTo("Sample Apartment");
				assertThat(lookup.pnu()).isEqualTo("1168010300101400001");
				assertThat(lookup.parcelAddress()).isEqualTo("Sample address");
			});

		repository.saveResolution(501L, ComplexMetadataResolution.resolved("ODC", new ComplexMetadata(
			8,
			999,
			new BigDecimal("12345.67"),
			new BigDecimal("2345.67"),
			new BigDecimal("98765.43"),
			new BigDecimal("22.50"),
			new BigDecimal("199.80"),
			LocalDate.of(2015, 3, 20)
		)));

		assertThat(complexMetadataState(501L))
			.containsEntry("metadata_status", "RESOLVED")
			.containsEntry("metadata_source", "ODC")
			.containsEntry("dong_cnt", 8)
			.containsEntry("unit_cnt", 740)
			.containsEntry("plat_area", new BigDecimal("12345.67"))
			.containsEntry("metadata_failure_reason", null);
		assertThat(complexMetadataState(501L).get("metadata_checked_at")).isNotNull();
	}

	@Test
	@DisplayName("metadata enrichment repository는 ambiguous 상태와 사유를 metadata overwrite 없이 기록한다")
	void recordsAmbiguousStatusWithoutMetadataOverwrite() {
		seedPendingComplex();
		JdbcComplexMetadataEnrichmentRepository repository = new JdbcComplexMetadataEnrichmentRepository(jdbcClient);

		repository.saveResolution(501L, ComplexMetadataResolution.ambiguous("ODC", "ODC PNU candidate ambiguous"));

		assertThat(complexMetadataState(501L))
			.containsEntry("metadata_status", "AMBIGUOUS")
			.containsEntry("metadata_source", "ODC")
			.containsEntry("unit_cnt", 740)
			.containsEntry("plat_area", null)
			.containsEntry("metadata_failure_reason", "ODC PNU candidate ambiguous");
	}

	private void seedPendingComplex() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400001', 'Sample address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, trade_name, unit_cnt)
			VALUES (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Sample Apartment', 'Sample Apartment', 740)
			""").update();
	}

	private java.util.Map<String, Object> complexMetadataState(long complexId) {
		return jdbcClient.sql("""
			SELECT
			    metadata_status,
			    metadata_source,
			    metadata_checked_at,
			    metadata_failure_reason,
			    dong_cnt,
			    unit_cnt,
			    plat_area
			FROM complex
			WHERE id = :complexId
			""")
			.param("complexId", complexId)
			.query((resultSet, rowNumber) -> {
				java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("metadata_status", resultSet.getString("metadata_status"));
				row.put("metadata_source", resultSet.getString("metadata_source"));
				row.put("metadata_checked_at", resultSet.getObject("metadata_checked_at"));
				row.put("metadata_failure_reason", resultSet.getString("metadata_failure_reason"));
				row.put("dong_cnt", resultSet.getObject("dong_cnt"));
				row.put("unit_cnt", resultSet.getObject("unit_cnt"));
				row.put("plat_area", resultSet.getBigDecimal("plat_area"));
				return row;
			})
			.single();
	}
}
