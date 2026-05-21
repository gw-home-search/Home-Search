package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeIngestService;
import com.home.application.ingest.OpenApiTradeItem;
import com.home.application.ingest.RawTradeIngestStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiTradeIngestServiceJdbcIntegrationTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("raw rows are saved before normalized insert and duplicate raw rows remain queryable")
	void rawRowsPrecedeNormalizedInsertAndDuplicateRawRowsRemainQueryable() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient)
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(rtmsItem("125,000"), rtmsItem(" 125000 "))
		));

		assertThat(result.rawSaved()).isEqualTo(2);
		assertThat(result.normalizedInserted()).isEqualTo(1);
		assertThat(result.duplicateSkipped()).isEqualTo(1);
		assertThat(rawCount()).isEqualTo(2);
		assertThat(tradeCount()).isEqualTo(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.NORMALIZED)).hasSize(1);
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.DUPLICATE)).hasSize(1);
		assertThat(firstRawCreatedAt()).isBeforeOrEqualTo(firstTradeCreatedAt());
	}

	@Test
	@DisplayName("local PostGIS matcher keeps failed RTMS matches queryable")
	void localPostgisMatcherKeepsFailedRtmsMatchesQueryable() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		OpenApiTradeIngestService service = new OpenApiTradeIngestService(
			rawRepository,
			tradeRepository,
			new JdbcComplexMatcher(jdbcClient)
		);

		IngestResult result = service.ingest(new OpenApiTradeIngestBatch(
			"RTMS",
			"11680",
			"202512",
			1,
			List.of(new OpenApiTradeItem(
				"101",
				"Missing Apartment",
				"APT-404",
				"125,000",
				1,
				12,
				2025,
				84.93,
				12,
				"999-1",
				"11680",
				"10300",
				"{\"aptSeq\":\"APT-404\",\"jibun\":\"999-1\"}"
			))
		));

		assertThat(result.rawSaved()).isEqualTo(1);
		assertThat(result.matchFailed()).isEqualTo(1);
		assertThat(tradeCount()).isZero();
		assertThat(rawRepository.findByStatus(RawTradeIngestStatus.MATCH_FAILED))
			.singleElement()
			.satisfies(record -> {
				assertThat(record.source()).isEqualTo("RTMS");
				assertThat(record.failureReason()).contains("APT-404");
				assertThat(record.failureReason()).contains("1168010300109990001");
			});
	}

	private OpenApiTradeItem rtmsItem(String dealAmount) {
		return new OpenApiTradeItem(
			"101",
			"Sample Apartment",
			"APT-501",
			dealAmount,
			1,
			12,
			2025,
			84.93,
			12,
			"140-1",
			"11680",
			"10300",
			"{\"aptSeq\":\"APT-501\",\"dealAmount\":\"%s\"}".formatted(dealAmount)
		);
	}

	private OffsetDateTime firstRawCreatedAt() {
		return jdbcClient.sql("SELECT created_at FROM raw_trade_ingest ORDER BY id LIMIT 1")
			.query(OffsetDateTime.class)
			.single();
	}

	private OffsetDateTime firstTradeCreatedAt() {
		return jdbcClient.sql("SELECT created_at FROM trade ORDER BY id LIMIT 1")
			.query(OffsetDateTime.class)
			.single();
	}
}
