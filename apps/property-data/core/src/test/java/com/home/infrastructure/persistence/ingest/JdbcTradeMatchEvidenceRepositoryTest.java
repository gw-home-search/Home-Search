package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.home.application.ingest.matching.TradeMatchEvidenceCommand;
import com.home.domain.ingest.matching.TradeMatchStatus;
import com.home.infrastructure.persistence.ingest.matching.JdbcTradeMatchEvidenceRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcTradeMatchEvidenceRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("trade match evidence는 raw_ingest_id 기준으로 지번/PNU와 후보 정보를 조회한다")
	void savesAndFindsMatchEvidenceByRawIngestId() {
		seedComplex();
		Long rawIngestId = seedRawIngest();
		JdbcTradeMatchEvidenceRepository repository = new JdbcTradeMatchEvidenceRepository(jdbcClient);

		repository.save(new TradeMatchEvidenceCommand(
			rawIngestId,
			"RTMS",
			"140-1",
			"140-1",
			"11680",
			"10300",
			"1",
			"0140",
			"0001",
			"1168010300101400001",
			null,
			"APT-501",
			"Observed Sample Apartment",
			TradeMatchStatus.MATCHED_NAME_VARIANT,
			"APTSEQ",
			501L,
			"COMPLEX-PK-501",
			1,
			List.of(501L),
			"observed RTMS name differs from master name"
		));

		var evidence = repository.findByRawIngestId(rawIngestId).orElseThrow();

		assertThat(evidence.rawIngestId()).isEqualTo(rawIngestId);
		assertThat(evidence.rawJibun()).isEqualTo("140-1");
		assertThat(evidence.normalizedJibun()).isEqualTo("140-1");
		assertThat(evidence.landCode()).isEqualTo("1");
		assertThat(evidence.bonbun()).isEqualTo("0140");
		assertThat(evidence.bubun()).isEqualTo("0001");
		assertThat(evidence.derivedPnu()).isEqualTo("1168010300101400001");
		assertThat(evidence.matchStatus()).isEqualTo(TradeMatchStatus.MATCHED_NAME_VARIANT);
		assertThat(evidence.matchPath()).isEqualTo("APTSEQ");
		assertThat(evidence.matchedComplexId()).isEqualTo(501L);
		assertThat(evidence.candidateCount()).isEqualTo(1);
		assertThat(evidence.candidateComplexIds()).containsExactly(501L);
		assertThat(evidence.failureReason()).contains("observed RTMS name");
	}

	@Test
	@DisplayName("trade match evidence는 raw payload와 source_key 전문을 저장하지 않는다")
	void omitsRawPayloadAndSourceKeyFromEvidenceStorage() {
		Long rawIngestId = seedRawIngest();
		JdbcTradeMatchEvidenceRepository repository = new JdbcTradeMatchEvidenceRepository(jdbcClient);

		repository.save(new TradeMatchEvidenceCommand(
			rawIngestId,
			"RTMS",
			null,
			null,
			"11680",
			"10300",
			null,
			null,
			null,
			null,
			"invalid jibun",
			"APT-501",
			"Observed Sample Apartment",
			TradeMatchStatus.PNU_UNAVAILABLE,
			null,
			null,
			null,
			0,
			List.of(),
			"invalid jibun"
		));

		String columns = jdbcClient.sql("""
			SELECT string_agg(column_name, ',' ORDER BY column_name)
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'trade_match_evidence'
			""")
			.query(String.class)
			.single();

		assertThat(columns).doesNotContain("payload");
		assertThat(columns).doesNotContain("source_key");
		assertThat(repository.findByRawIngestId(rawIngestId)).isPresent();
	}

	private Long seedRawIngest() {
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
			VALUES (
			    'RTMS',
			    'RTMS:test-source-key',
			    '11680',
			    '202512',
			    1,
			    '{"jibun":"140-1"}',
			    'hash',
			    'RECEIVED'
			)
			RETURNING id
			""")
			.query(Long.class)
			.single();
	}
}
