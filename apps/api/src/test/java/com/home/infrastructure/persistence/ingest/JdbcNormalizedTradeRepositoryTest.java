package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import com.home.application.ingest.NormalizedTradeCommand;
import com.home.application.ingest.RawTradeIngestRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcNormalizedTradeRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("source/source_key duplicate insert는 normalized trade를 중복 생성하지 않는다")
	void sourceKeyDuplicateDoesNotCreateSecondTrade() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		Long rawId = rawRepository.save(rawRecord("rtms-source-key-1")).id();

		boolean firstInsert = tradeRepository.insertIfAbsent(command(rawId, "rtms-source-key-1"));
		boolean duplicateInsert = tradeRepository.insertIfAbsent(command(rawId, "rtms-source-key-1"));

		assertThat(firstInsert).isTrue();
		assertThat(duplicateInsert).isFalse();
		assertThat(tradeRepository.existsBySourceAndSourceKey("RTMS", "rtms-source-key-1")).isTrue();
		assertThat(tradeCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("fallback identity duplicate insert는 normalized trade를 중복 생성하지 않는다")
	void fallbackDuplicateDoesNotCreateSecondTradeAndKeepsSourceKeyTraceLinked() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		Long firstRawId = rawRepository.save(rawRecord("rtms-source-key-1")).id();
		Long secondRawId = rawRepository.save(rawRecord("rtms-source-key-2")).id();

		boolean firstInsert = tradeRepository.insertIfAbsent(command(firstRawId, "rtms-source-key-1"));
		boolean fallbackDuplicate = tradeRepository.insertIfAbsent(command(secondRawId, "rtms-source-key-2"));

		assertThat(firstInsert).isTrue();
		assertThat(fallbackDuplicate).isFalse();
		assertThat(tradeCount()).isEqualTo(1);
		Long existingTradeId = onlyTradeId();
		assertThat(registryTradeIds()).containsExactly(existingTradeId, existingTradeId);
	}

	private RawTradeIngestRecord rawRecord(String sourceKey) {
		return RawTradeIngestRecord.received(
			"RTMS",
			sourceKey,
			"11680",
			"202512",
			1,
			"{\"sourceKey\":\"%s\"}".formatted(sourceKey),
			"payload-hash-" + sourceKey
		);
	}

	private NormalizedTradeCommand command(Long rawIngestId, String sourceKey) {
		return new NormalizedTradeCommand(
			rawIngestId,
			501L,
			LocalDate.of(2025, 12, 1),
			125000L,
			12,
			84.93,
			"101",
			"RTMS",
			sourceKey,
			"COMPLEX-PK-501",
			"APT-501"
		);
	}

	private Long onlyTradeId() {
		return jdbcClient.sql("SELECT id FROM trade")
			.query(Long.class)
			.single();
	}

	private List<Long> registryTradeIds() {
		return jdbcClient.sql("""
			SELECT trade_id
			FROM trade_source_key_registry
			ORDER BY source_key
			""")
			.query(Long.class)
			.list();
	}
}
