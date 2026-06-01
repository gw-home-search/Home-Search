package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.home.application.ingest.ComplexMetadata;
import com.home.application.ingest.ComplexMetadataFailureKind;
import com.home.application.ingest.ComplexMetadataLookup;
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
				assertThat(lookup.attempts()).isZero();
			});

		repository.saveResolution(501L, ComplexMetadataResolution.classify("ODC", new ComplexMetadata(
			8,
			999,
			new BigDecimal("12345.67"),
			new BigDecimal("2345.67"),
			new BigDecimal("98765.43"),
			new BigDecimal("22.50"),
			new BigDecimal("199.80"),
			LocalDate.of(2015, 3, 20)
		)), null);

		assertThat(complexMetadataState(501L))
			.containsEntry("metadata_status", "RESOLVED")
			.containsEntry("metadata_source", "ODC")
			.containsEntry("metadata_attempts", 1)
			.containsEntry("metadata_failure_kind", null)
			.containsEntry("metadata_next_attempt_at", null)
			.containsEntry("dong_cnt", 8)
			.containsEntry("unit_cnt", 740)
			.containsEntry("plat_area", new BigDecimal("12345.67"))
			.containsEntry("metadata_failure_reason", null);
		assertThat(complexMetadataState(501L).get("metadata_checked_at")).isNotNull();
		assertThat(attemptRows(501L)).singleElement()
			.satisfies(row -> assertThat(row)
				.containsEntry("attempt_no", 1)
				.containsEntry("status", "RESOLVED")
				.containsEntry("source", "ODC")
				.containsEntry("failure_kind", null)
				.containsEntry("failure_reason", null));
	}

	@Test
	@DisplayName("metadata enrichment repository는 ambiguous 상태와 사유를 metadata overwrite 없이 기록한다")
	void recordsAmbiguousStatusWithoutMetadataOverwrite() {
		seedPendingComplex();
		JdbcComplexMetadataEnrichmentRepository repository = new JdbcComplexMetadataEnrichmentRepository(jdbcClient);

		repository.saveResolution(
			501L,
			ComplexMetadataResolution.ambiguous("ODC", "ODC PNU candidate ambiguous"),
			null
		);

		assertThat(complexMetadataState(501L))
			.containsEntry("metadata_status", "AMBIGUOUS")
			.containsEntry("metadata_source", "ODC")
			.containsEntry("metadata_attempts", 1)
			.containsEntry("metadata_failure_kind", "AMBIGUOUS")
			.containsEntry("unit_cnt", 740)
			.containsEntry("plat_area", null)
			.containsEntry("metadata_failure_reason", "ODC PNU candidate ambiguous");
		assertThat(attemptRows(501L)).singleElement()
			.satisfies(row -> assertThat(row)
				.containsEntry("attempt_no", 1)
				.containsEntry("status", "AMBIGUOUS")
				.containsEntry("failure_kind", "AMBIGUOUS"));
	}

	@Test
	@DisplayName("metadata enrichment repository는 PARTIAL metadata와 다음 시도 시각을 저장하고 이력을 남긴다")
	void recordsPartialMetadataRetryStateAndAttemptHistory() {
		seedPendingComplex();
		JdbcComplexMetadataEnrichmentRepository repository = new JdbcComplexMetadataEnrichmentRepository(jdbcClient);
		Instant nextAttemptAt = Instant.parse("2026-06-15T00:00:00Z");

		repository.saveResolution(501L, ComplexMetadataResolution.classify("BLD", new ComplexMetadata(
			null,
			999,
			new BigDecimal("12345.67"),
			null,
			null,
			null,
			null,
			null
		)), nextAttemptAt);

		assertThat(complexMetadataState(501L))
			.containsEntry("metadata_status", "PARTIAL")
			.containsEntry("metadata_attempts", 1)
			.containsEntry("metadata_failure_kind", null)
			.containsEntry("unit_cnt", 740)
			.containsEntry("plat_area", new BigDecimal("12345.67"));
		assertThat(complexMetadataState(501L).get("metadata_next_attempt_at")).isNotNull();
		assertThat(attemptRows(501L)).singleElement()
			.satisfies(row -> assertThat(row)
				.containsEntry("attempt_no", 1)
				.containsEntry("status", "PARTIAL")
				.containsEntry("source", "BLD")
				.containsEntry("failure_kind", null));
	}

	@Test
	@DisplayName("metadata enrichment repository는 실패 kind와 next attempt를 저장하고 attempts를 누적한다")
	void recordsFailureKindNextAttemptAndIncrementingAttempts() {
		seedPendingComplex();
		JdbcComplexMetadataEnrichmentRepository repository = new JdbcComplexMetadataEnrichmentRepository(jdbcClient);
		Instant nextAttemptAt = Instant.parse("2026-06-02T00:00:00Z");

		repository.saveResolution(
			501L,
			ComplexMetadataResolution.failed("ODC", ComplexMetadataFailureKind.TRANSIENT, "timeout"),
			nextAttemptAt
		);
		repository.saveResolution(
			501L,
			ComplexMetadataResolution.unavailable("ODC", ComplexMetadataFailureKind.SOURCE_MISSING, "candidate unavailable"),
			null
		);

		assertThat(complexMetadataState(501L))
			.containsEntry("metadata_status", "UNAVAILABLE")
			.containsEntry("metadata_attempts", 2)
			.containsEntry("metadata_failure_kind", "SOURCE_MISSING")
			.containsEntry("metadata_failure_reason", "candidate unavailable")
			.containsEntry("metadata_next_attempt_at", null);
		assertThat(attemptRows(501L)).hasSize(2);
		assertThat(attemptRows(501L)).extracting(row -> row.get("attempt_no"))
			.containsExactly(2, 1);
	}

	@Test
	@DisplayName("metadata enrichment repository는 next_attempt_at이 지난 재시도 대상만 다시 조회한다")
	void findsDueRetryableComplexesButSkipsTerminalAndFutureRows() {
		seedPendingComplex();
		seedComplex(
			502,
			1002,
			"1168010300101410000",
			"APT-502",
			"Failed Apartment",
			"FAILED",
			1,
			Instant.parse("2026-05-31T00:00:00Z")
		);
		seedComplex(
			503,
			1003,
			"1168010300101420000",
			"APT-503",
			"Future Failed Apartment",
			"FAILED",
			1,
			Instant.parse("2999-06-30T00:00:00Z")
		);
		seedComplex(
			504,
			1004,
			"1168010300101430000",
			"APT-504",
			"Ambiguous Apartment",
			"AMBIGUOUS",
			1,
			null
		);
		seedComplex(
			505,
			1005,
			"1168010300101440000",
			"APT-505",
			"Resolved Apartment",
			"RESOLVED",
			1,
			null
		);
		JdbcComplexMetadataEnrichmentRepository repository = new JdbcComplexMetadataEnrichmentRepository(jdbcClient);

		assertThat(repository.findPending(10))
			.extracting(ComplexMetadataLookup::complexId)
			.containsExactly(501L, 502L);
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

	private void seedComplex(
		long complexId,
		long parcelId,
		String pnu,
		String aptSeq,
		String aptName,
		String metadataStatus,
		int attempts,
		Instant nextAttemptAt
	) {
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (:parcelId, 1, :pnu, :address, 37.5123, 127.0456)
			""")
			.param("parcelId", parcelId)
			.param("pnu", pnu)
			.param("address", aptName + " address")
			.update();
		jdbcClient.sql("""
			INSERT INTO complex (
			    id,
			    parcel_id,
			    complex_pk,
			    apt_seq,
			    name,
			    trade_name,
			    metadata_status,
			    metadata_attempts,
			    metadata_next_attempt_at
			)
			VALUES (
			    :complexId,
			    :parcelId,
			    :complexPk,
			    :aptSeq,
			    :aptName,
			    :aptName,
			    :metadataStatus,
			    :attempts,
			    :nextAttemptAt
			)
			""")
			.param("complexId", complexId)
			.param("parcelId", parcelId)
			.param("complexPk", "COMPLEX-PK-" + complexId)
			.param("aptSeq", aptSeq)
			.param("aptName", aptName)
			.param("metadataStatus", metadataStatus)
			.param("attempts", attempts)
			.param("nextAttemptAt", nextAttemptAt == null ? null : OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC))
			.update();
	}

	private Map<String, Object> complexMetadataState(long complexId) {
		return jdbcClient.sql("""
			SELECT
			    metadata_status,
			    metadata_source,
			    metadata_checked_at,
			    metadata_failure_reason,
			    metadata_attempts,
			    metadata_failure_kind,
			    metadata_next_attempt_at,
			    dong_cnt,
			    unit_cnt,
			    plat_area
			FROM complex
			WHERE id = :complexId
			""")
			.param("complexId", complexId)
			.query((resultSet, rowNumber) -> {
				Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("metadata_status", resultSet.getString("metadata_status"));
				row.put("metadata_source", resultSet.getString("metadata_source"));
				row.put("metadata_checked_at", resultSet.getObject("metadata_checked_at"));
				row.put("metadata_failure_reason", resultSet.getString("metadata_failure_reason"));
				row.put("metadata_attempts", resultSet.getObject("metadata_attempts"));
				row.put("metadata_failure_kind", resultSet.getString("metadata_failure_kind"));
				row.put("metadata_next_attempt_at", resultSet.getObject("metadata_next_attempt_at"));
				row.put("dong_cnt", resultSet.getObject("dong_cnt"));
				row.put("unit_cnt", resultSet.getObject("unit_cnt"));
				row.put("plat_area", resultSet.getBigDecimal("plat_area"));
				return row;
			})
			.single();
	}

	private List<Map<String, Object>> attemptRows(long complexId) {
		return jdbcClient.sql("""
			SELECT attempt_no, status, source, failure_kind, failure_reason, next_attempt_at
			FROM complex_metadata_enrichment_attempt
			WHERE complex_id = :complexId
			ORDER BY observed_at DESC, id DESC
			""")
			.param("complexId", complexId)
			.query((resultSet, rowNumber) -> {
				Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("attempt_no", resultSet.getObject("attempt_no"));
				row.put("status", resultSet.getString("status"));
				row.put("source", resultSet.getString("source"));
				row.put("failure_kind", resultSet.getString("failure_kind"));
				row.put("failure_reason", resultSet.getString("failure_reason"));
				row.put("next_attempt_at", resultSet.getObject("next_attempt_at"));
				return row;
			})
			.list();
	}
}
