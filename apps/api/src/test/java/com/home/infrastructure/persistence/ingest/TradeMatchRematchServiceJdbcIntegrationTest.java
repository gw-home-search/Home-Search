package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.home.application.ingest.RawTradeIngestStatus;
import com.home.application.ingest.TradeMatchRematchService;
import com.home.application.ingest.TradeMatchStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TradeMatchRematchServiceJdbcIntegrationTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("MATCH_FAILED raw는 complex coverage 보강 후 외부 RTMS 재호출 없이 rematch되어 normalized trade가 된다")
	void rematchesHeldRawAfterComplexCoverageImprovesWithoutExternalFetch() {
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
			    failure_reason,
			    processed_at
			)
			VALUES (
			    93001,
			    'RTMS',
			    'held-source-key',
			    '11680',
			    '202512',
			    1,
			    '{"aptDong":"101","aptNm":"Sample Apartment","aptSeq":"APT-501","dealAmount":"125,000","dealDay":15,"dealMonth":12,"dealYear":2025,"excluUseAr":84.93,"floor":12,"jibun":"140-1","sggCd":"11680","umdCd":"10300"}',
			    'hash-held-source-key',
			    'MATCH_FAILED',
			    'old unmatched evidence',
			    now()
			)
			""").update();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcTradeMatchEvidenceRepository evidenceRepository = new JdbcTradeMatchEvidenceRepository(jdbcClient);
		TradeMatchRematchService service = new TradeMatchRematchService(
			rawRepository,
			new JdbcNormalizedTradeRepository(jdbcClient, transactionTemplate),
			new JdbcComplexMatcher(jdbcClient),
			evidenceRepository,
			new RtmsRawTradeItemParser(new ObjectMapper())
		);

		var result = service.rematchHeld(10);

		assertThat(result.processed()).isEqualTo(1);
		assertThat(result.normalized()).isEqualTo(1);
		assertThat(tradeCount()).isEqualTo(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED))
			.singleElement()
			.satisfies(raw -> assertThat(raw.id()).isEqualTo(93001L));
		assertThat(evidenceRepository.findByRawIngestId(93001L))
			.hasValueSatisfying(evidence -> {
				assertThat(evidence.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED);
				assertThat(evidence.matchedComplexId()).isEqualTo(501L);
			});
	}
}
