package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import com.home.application.ingest.ComplexMatchResult;
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
			item -> ComplexMatchResult.matched(501L, "COMPLEX-PK-501", "aptSeq")
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
