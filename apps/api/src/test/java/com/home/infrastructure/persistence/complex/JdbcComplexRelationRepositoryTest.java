package com.home.infrastructure.persistence.complex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.LocalDate;

import com.home.domain.complex.relation.ComplexRelationConfidence;
import com.home.domain.complex.relation.ComplexRelationClassifier;
import com.home.domain.complex.relation.ComplexRelationType;
import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcComplexRelationRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("complex relation repository는 같은 parcel의 complex별 active 거래 span을 집계한다")
	void aggregatesTradeSpansByComplexUnderParcel() {
		seedParcelComplexes();
		Long rawId = insertRawIngest("relation-overlap");
		insertTrade(rawId, 501L, LocalDate.of(2024, 1, 1), "rtms-relation-501-1");
		insertTrade(rawId, 501L, LocalDate.of(2025, 6, 1), "rtms-relation-501-2");
		insertTrade(rawId, 502L, LocalDate.of(2025, 1, 1), "rtms-relation-502-1");
		insertTrade(rawId, 502L, LocalDate.of(2025, 12, 1), "rtms-relation-502-2");
		insertDeletedTrade(rawId, 502L, LocalDate.of(2026, 1, 1), "rtms-relation-502-canceled");

		JdbcComplexRelationRepository repository = new JdbcComplexRelationRepository(jdbcClient);

		assertThat(repository.findTradeSpansByParcelId(1001L))
			.extracting(
				span -> span.complexId(),
				span -> span.firstDeal(),
				span -> span.lastDeal(),
				span -> span.tradeCount()
			)
			.containsExactly(
				tuple(501L, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 6, 1), 2L),
				tuple(502L, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 1), 2L)
			);
	}

	@Test
	@DisplayName("complex relation classifier는 JDBC span으로 CONCURRENT와 REDEVELOPED를 구분한다")
	void classifiesConcurrentAndRedevelopedFromJdbcSpans() {
		seedParcelComplexes();
		Long rawId = insertRawIngest("relation-classification");
		insertTrade(rawId, 501L, LocalDate.of(2024, 1, 1), "rtms-concurrent-501-1");
		insertTrade(rawId, 501L, LocalDate.of(2025, 6, 1), "rtms-concurrent-501-2");
		insertTrade(rawId, 502L, LocalDate.of(2025, 1, 1), "rtms-concurrent-502-1");
		insertTrade(rawId, 502L, LocalDate.of(2025, 12, 1), "rtms-concurrent-502-2");
		seedRedevelopmentParcel();
		insertTrade(rawId, 601L, LocalDate.of(2016, 1, 1), "rtms-redeveloped-601-1");
		insertTrade(rawId, 601L, LocalDate.of(2018, 1, 1), "rtms-redeveloped-601-2");
		insertTrade(rawId, 602L, LocalDate.of(2020, 1, 1), "rtms-redeveloped-602-1");
		insertTrade(rawId, 602L, LocalDate.of(2025, 1, 1), "rtms-redeveloped-602-2");
		JdbcComplexRelationRepository repository = new JdbcComplexRelationRepository(jdbcClient);
		ComplexRelationClassifier classifier = new ComplexRelationClassifier();

		assertThat(classifier.classify(repository.findTradeSpansByParcelId(1001L)).type())
			.isEqualTo(ComplexRelationType.CONCURRENT);
		var redevelopment = classifier.classify(repository.findTradeSpansByParcelId(1002L));
		assertThat(redevelopment.type()).isEqualTo(ComplexRelationType.REDEVELOPED);
		assertThat(redevelopment.confidence()).isEqualTo(ComplexRelationConfidence.HIGH);
	}

	@Test
	@DisplayName("complex relation classifier는 JDBC span의 거래 표본이 작으면 큰 공백도 UNKNOWN으로 남긴다")
	void keepsUnknownForSparseJdbcTradeSpans() {
		seedParcelComplexes();
		seedSparseTradeParcel();
		Long rawId = insertRawIngest("relation-sparse-trade");
		insertTrade(rawId, 701L, LocalDate.of(2016, 1, 1), "rtms-sparse-701-1");
		insertTrade(rawId, 702L, LocalDate.of(2020, 1, 1), "rtms-sparse-702-1");
		JdbcComplexRelationRepository repository = new JdbcComplexRelationRepository(jdbcClient);
		ComplexRelationClassifier classifier = new ComplexRelationClassifier();

		var classification = classifier.classify(repository.findTradeSpansByParcelId(1003L));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.UNKNOWN);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.NONE);
		assertThat(classification.reason()).contains("sample too small");
	}

	private void seedParcelComplexes() {
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

	private void seedRedevelopmentParcel() {
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1002, 1, '1168010300101400002', 'Redeveloped address', 37.5124, 127.0457)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, use_date)
			VALUES
			    (601, 1002, 'COMPLEX-PK-601', 'APT-601', 'Old Apartment', DATE '1995-01-01'),
			    (602, 1002, 'COMPLEX-PK-602', 'APT-602', 'New Apartment', DATE '2020-01-01')
			""").update();
	}

	private void seedSparseTradeParcel() {
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1003, 1, '1168010300101400003', 'Sparse trade address', 37.5125, 127.0458)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, use_date)
			VALUES
			    (701, 1003, 'COMPLEX-PK-701', 'APT-701', 'Sparse Apartment A', NULL),
			    (702, 1003, 'COMPLEX-PK-702', 'APT-702', 'Sparse Apartment B', NULL)
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
		insertTrade(rawId, complexId, dealDate, sourceKey, false);
	}

	private void insertDeletedTrade(Long rawId, Long complexId, LocalDate dealDate, String sourceKey) {
		insertTrade(rawId, complexId, dealDate, sourceKey, true);
	}

	private void insertTrade(Long rawId, Long complexId, LocalDate dealDate, String sourceKey, boolean deleted) {
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
			    apt_seq,
			    deleted_at
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
			    :aptSeq,
			    CASE WHEN :deleted THEN now() ELSE NULL END
			)
			""")
			.param("rawId", rawId)
			.param("complexId", complexId)
			.param("dealDate", dealDate)
			.param("sourceKey", sourceKey)
			.param("complexPk", "COMPLEX-PK-" + complexId)
			.param("aptSeq", "APT-" + complexId)
			.param("deleted", deleted)
			.update();
	}
}
