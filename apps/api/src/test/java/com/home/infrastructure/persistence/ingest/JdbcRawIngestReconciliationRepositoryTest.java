package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.reconciliation.RawIngestReconciliationService;
import com.home.application.ingest.raw.RawTradeIngestStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRawIngestReconciliationRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("raw reconciliation repository는 RECEIVED raw 중 registry가 active trade에 연결된 row만 조회한다")
	void findsReceivedRowsLinkedToActiveTradeOnly() {
		seedComplex();
		seedRaw(91001, "linked-active", "RECEIVED");
		seedRaw(91002, "linked-null", "RECEIVED");
		seedRaw(91003, "already-normalized", "NORMALIZED");
		seedTrade(99001, 91001, "linked-active", false);
		seedRegistry("linked-active", 91001, 99001L);
		seedRegistry("linked-null", 91002, null);
		seedRegistry("already-normalized", 91003, 99001L);
		JdbcRawIngestReconciliationRepository repository = new JdbcRawIngestReconciliationRepository(jdbcClient);

		assertThat(repository.findReceivedRowsLinkedToActiveTrade(10))
			.singleElement()
			.satisfies(candidate -> {
				assertThat(candidate.rawIngestId()).isEqualTo(91001L);
				assertThat(candidate.tradeId()).isEqualTo(99001L);
			});
		RawIngestReconciliationService service = new RawIngestReconciliationService(
			repository,
			new JdbcRawTradeIngestRepository(jdbcClient)
		);

		assertThat(service.reconcileReceived(10).normalized()).isEqualTo(1);
		assertThat(new JdbcRawTradeIngestRepository(jdbcClient).findByStatus(RawTradeIngestStatus.NORMALIZED))
			.extracting(raw -> raw.id())
			.contains(91001L, 91003L);
	}

	private void seedRaw(long id, String sourceKey, String status) {
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
			    status
			)
			VALUES (:id, 'RTMS', :sourceKey, '11680', '202512', 1, '{}', :payloadHash, :status)
			""")
			.param("id", id)
			.param("sourceKey", sourceKey)
			.param("payloadHash", "hash-" + sourceKey)
			.param("status", status)
			.update();
	}

	private void seedTrade(long id, long rawIngestId, String sourceKey, boolean deleted) {
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
			    raw_ingest_id,
			    deleted_at
			)
			VALUES (
			    :id,
			    501,
			    DATE '2025-12-15',
			    125000,
			    12,
			    84.93,
			    '101',
			    'RTMS',
			    :sourceKey,
			    'COMPLEX-PK-501',
			    'APT-501',
			    :rawIngestId,
			    CASE WHEN :deleted THEN now() ELSE NULL END
			)
			""")
			.param("id", id)
			.param("sourceKey", sourceKey)
			.param("rawIngestId", rawIngestId)
			.param("deleted", deleted)
			.update();
	}

	private void seedRegistry(String sourceKey, long rawIngestId, Long tradeId) {
		jdbcClient.sql("""
			INSERT INTO trade_source_key_registry (source, source_key, raw_ingest_id, trade_id)
			VALUES ('RTMS', :sourceKey, :rawIngestId, :tradeId)
			""")
			.param("sourceKey", sourceKey)
			.param("rawIngestId", rawIngestId)
			.param("tradeId", tradeId)
			.update();
	}
}
