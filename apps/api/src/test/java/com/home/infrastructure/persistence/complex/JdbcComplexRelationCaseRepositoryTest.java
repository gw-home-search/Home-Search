package com.home.infrastructure.persistence.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDate;
import java.util.List;

import com.home.application.complex.ComplexRelationClassifier;
import com.home.application.complex.ComplexRelationCaseRecord;
import com.home.application.complex.ComplexRelationClassification;
import com.home.application.complex.ComplexRelationConfidence;
import com.home.application.complex.ComplexRelationType;
import com.home.application.complex.ComplexRelationCaseMember;
import com.home.application.complex.ComplexTradeSpan;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcComplexRelationCaseRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("complex relation case repository는 case_key 기준으로 판정 결과와 후보 complex snapshot을 교체 저장한다")
	void savesRelationCaseAndReplacesMemberSnapshotsByCaseKey() {
		seedRelationParcel();
		Long rawId = insertRawIngest("relation-case");
		insertTrade(rawId, 501L, LocalDate.of(2024, 1, 1), "rtms-relation-case-501-1");
		insertTrade(rawId, 501L, LocalDate.of(2025, 6, 1), "rtms-relation-case-501-2");
		insertTrade(rawId, 502L, LocalDate.of(2025, 1, 1), "rtms-relation-case-502-1");
		insertTrade(rawId, 502L, LocalDate.of(2025, 12, 1), "rtms-relation-case-502-2");
		JdbcComplexRelationRepository spanRepository = new JdbcComplexRelationRepository(jdbcClient);
		var classification = new ComplexRelationClassifier().classify(spanRepository.findTradeSpansByParcelId(1001L));
		JdbcComplexRelationCaseRepository caseRepository = new JdbcComplexRelationCaseRepository(jdbcClient);

		var first = caseRepository.save(1001L, classification, "relation-classifier-stable");
		var second = caseRepository.save(1001L, unrelatedReplacement(), "relation-classifier-stable");

		assertThat(first.id()).isNotNull();
		assertThat(second.id()).isEqualTo(first.id());
		assertThat(caseRepository.findByParcelId(1001L))
			.extracting(
				record -> record.caseKey(),
				record -> record.parcelId(),
				record -> record.pnu(),
				record -> record.relationType(),
				record -> record.relationConfidence(),
				record -> record.classifierVersion()
			)
			.containsExactly(
				tuple(
					"complex-relation:1168010300101400001",
					1001L,
					"1168010300101400001",
					ComplexRelationType.UNRELATED,
					ComplexRelationConfidence.MEDIUM,
					"relation-classifier-stable"
				)
			);
		ComplexRelationCaseRecord saved = caseRepository.findByParcelId(1001L).get(0);
		assertThat(saved.evidenceJson())
			.contains("\"relationType\": \"UNRELATED\"")
			.contains("\"spanCount\": 1");
		assertThat(saved.members())
			.extracting(
				ComplexRelationCaseMember::complexId,
				ComplexRelationCaseMember::complexPk,
				ComplexRelationCaseMember::aptSeq,
				ComplexRelationCaseMember::name,
				ComplexRelationCaseMember::firstDeal,
				ComplexRelationCaseMember::lastDeal,
				ComplexRelationCaseMember::tradeCount,
				ComplexRelationCaseMember::useDate
			)
			.containsExactly(
				tuple(
					501L,
					"COMPLEX-PK-501",
					"APT-501",
					"Sample Apartment A",
					LocalDate.of(2024, 1, 1),
					LocalDate.of(2024, 1, 1),
					1L,
					LocalDate.of(2010, 1, 1)
				)
			);
	}

	private ComplexRelationClassification unrelatedReplacement() {
		return new ComplexRelationClassification(
			ComplexRelationType.UNRELATED,
			List.of(new ComplexTradeSpan(
				501L,
				"COMPLEX-PK-501",
				"APT-501",
				"Sample Apartment A",
				LocalDate.of(2024, 1, 1),
				LocalDate.of(2024, 1, 1),
				1L,
				LocalDate.of(2010, 1, 1)
			)),
			"manual evidence separates relation candidates",
			ComplexRelationConfidence.MEDIUM
		);
	}

	private void seedRelationParcel() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400001', 'Sample address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, use_date)
			VALUES
			    (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Sample Apartment A', DATE '2010-01-01'),
			    (502, 1001, 'COMPLEX-PK-502', 'APT-502', 'Sample Apartment B', DATE '2015-01-01')
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

	private void insertTrade(Long rawId, Long complexId, LocalDate dealDate, String sourceKey) {
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
			    '101',
			    'RTMS',
			    :sourceKey,
			    :complexPk,
			    :aptSeq
			)
			""")
			.param("rawId", rawId)
			.param("complexId", complexId)
			.param("dealDate", dealDate)
			.param("sourceKey", sourceKey)
			.param("complexPk", "COMPLEX-PK-" + complexId)
			.param("aptSeq", "APT-" + complexId)
			.update();
	}
}
