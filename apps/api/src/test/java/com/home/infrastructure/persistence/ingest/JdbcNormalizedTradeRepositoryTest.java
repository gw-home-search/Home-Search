package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

	@Test
	@DisplayName("fallback identity는 3자리+ excl_area가 2자리 반올림으로 같아지면 duplicate로 처리한다")
	void fallbackDuplicateRoundsExclAreaBeforeMatchingAndInsert() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		Long firstRawId = rawRepository.save(rawRecord("rtms-source-key-1")).id();
		Long secondRawId = rawRepository.save(rawRecord("rtms-source-key-2")).id();

		boolean firstInsert = tradeRepository.insertIfAbsent(command(firstRawId, "rtms-source-key-1", "101", 84.93));

		assertThat(firstInsert).isTrue();
		assertThatCode(() -> tradeRepository.insertIfAbsent(
			command(secondRawId, "rtms-source-key-2", "101", 84.931)
		))
			.doesNotThrowAnyException();
		assertThat(tradeCount()).isEqualTo(1);
		assertThat(registryTradeIds()).containsExactly(onlyTradeId(), onlyTradeId());
	}

	@Test
	@DisplayName("fallback identity는 같은 조건이어도 aptDong이 다르면 별도 거래로 보존한다")
	void fallbackIdentityKeepsDifferentAptDongTradesSeparate() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		Long firstRawId = rawRepository.save(rawRecord("rtms-source-key-1")).id();
		Long secondRawId = rawRepository.save(rawRecord("rtms-source-key-2")).id();

		boolean firstInsert = tradeRepository.insertIfAbsent(command(firstRawId, "rtms-source-key-1", "101"));
		boolean secondInsert = tradeRepository.insertIfAbsent(command(secondRawId, "rtms-source-key-2", "102"));

		assertThat(firstInsert).isTrue();
		assertThat(secondInsert).isTrue();
		assertThat(tradeCount()).isEqualTo(2);
		assertThat(activeAptDongs()).containsExactly("101", "102");
	}

	@Test
	@DisplayName("fallback identity는 aptDong이 누락된 기존 거래와 보강된 거래를 duplicate로 처리한다")
	void fallbackIdentityTreatsMissingAptDongAsUnknownDuplicate() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		Long firstRawId = rawRepository.save(rawRecord("rtms-source-key-missing-dong")).id();
		Long secondRawId = rawRepository.save(rawRecord("rtms-source-key-filled-dong")).id();

		boolean firstInsert = tradeRepository.insertIfAbsent(command(firstRawId, "rtms-source-key-missing-dong", null));
		boolean filledDongDuplicate = tradeRepository.insertIfAbsent(
			command(secondRawId, "rtms-source-key-filled-dong", "101")
		);

		assertThat(firstInsert).isTrue();
		assertThat(filledDongDuplicate).isFalse();
		assertThat(tradeCount()).isEqualTo(1);
		assertThat(activeAptDongs()).containsExactly((String) null);
		assertThat(registryTradeIds()).containsExactly(onlyTradeId(), onlyTradeId());
	}

	@Test
	@DisplayName("fallback identity는 aptDong이 보강된 기존 거래와 누락된 거래도 duplicate로 처리한다")
	void fallbackIdentityTreatsLaterMissingAptDongAsUnknownDuplicate() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		Long firstRawId = rawRepository.save(rawRecord("rtms-source-key-filled-dong")).id();
		Long secondRawId = rawRepository.save(rawRecord("rtms-source-key-missing-dong")).id();

		boolean firstInsert = tradeRepository.insertIfAbsent(command(firstRawId, "rtms-source-key-filled-dong", "101"));
		boolean missingDongDuplicate = tradeRepository.insertIfAbsent(
			command(secondRawId, "rtms-source-key-missing-dong", null)
		);

		assertThat(firstInsert).isTrue();
		assertThat(missingDongDuplicate).isFalse();
		assertThat(tradeCount()).isEqualTo(1);
		assertThat(activeAptDongs()).containsExactly("101");
		assertThat(registryTradeIds()).containsExactly(onlyTradeId(), onlyTradeId());
	}

	@Test
	@DisplayName("fallback identity는 aptDong이 없는 row가 복수 동 후보에 걸리면 임의 거래에 연결하지 않는다")
	void fallbackIdentityDoesNotAttachMissingAptDongToAmbiguousCandidates() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		Long firstRawId = rawRepository.save(rawRecord("rtms-source-key-101")).id();
		Long secondRawId = rawRepository.save(rawRecord("rtms-source-key-102")).id();
		Long missingDongRawId = rawRepository.save(rawRecord("rtms-source-key-missing-dong")).id();

		boolean firstInsert = tradeRepository.insertIfAbsent(command(firstRawId, "rtms-source-key-101", "101"));
		boolean secondInsert = tradeRepository.insertIfAbsent(command(secondRawId, "rtms-source-key-102", "102"));
		boolean ambiguousMissingDong = tradeRepository.insertIfAbsent(
			command(missingDongRawId, "rtms-source-key-missing-dong", null)
		);
		boolean canceled = tradeRepository.cancelBySourceAndSourceKey(
			"RTMS",
			"rtms-source-key-missing-dong",
			missingDongRawId
		);

		assertThat(firstInsert).isTrue();
		assertThat(secondInsert).isTrue();
		assertThat(ambiguousMissingDong).isFalse();
		assertThat(canceled).isFalse();
		assertThat(tradeCount()).isEqualTo(2);
		assertThat(activeTradeCount()).isEqualTo(2);
		assertThat(deletedTradeCount()).isZero();
		assertThat(activeAptDongs()).containsExactly("101", "102");
		assertThat(registryTradeId("rtms-source-key-missing-dong")).isNull();
	}

	@Test
	@DisplayName("canceled source_key는 연결된 normalized trade를 public 조회에서 제외한다")
	void cancelSourceKeyMarksLinkedTradeDeleted() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		Long rawId = rawRepository.save(rawRecord("rtms-source-key-1")).id();
		tradeRepository.insertIfAbsent(command(rawId, "rtms-source-key-1"));

		boolean canceled = tradeRepository.cancelBySourceAndSourceKey("RTMS", "rtms-source-key-1", rawId);

		assertThat(canceled).isTrue();
		assertThat(activeTradeCount()).isZero();
		assertThat(deletedTradeCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("canceled source_key가 먼저 오면 registry를 선점해 이후 normalized insert를 막는다")
	void cancelSourceKeyBeforeNormalizedInsertReservesRegistry() {
		seedComplex();
		JdbcRawTradeIngestRepository rawRepository = new JdbcRawTradeIngestRepository(jdbcClient);
		JdbcNormalizedTradeRepository tradeRepository = new JdbcNormalizedTradeRepository(
			jdbcClient,
			transactionTemplate
		);
		Long cancelRawId = rawRepository.save(rawRecord("rtms-source-key-1")).id();

		boolean canceled = tradeRepository.cancelBySourceAndSourceKey("RTMS", "rtms-source-key-1", cancelRawId);
		boolean inserted = tradeRepository.insertIfAbsent(command(cancelRawId, "rtms-source-key-1"));

		assertThat(canceled).isFalse();
		assertThat(inserted).isFalse();
		assertThat(tradeRepository.existsBySourceAndSourceKey("RTMS", "rtms-source-key-1")).isTrue();
		assertThat(tradeCount()).isZero();
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
		return command(rawIngestId, sourceKey, "101");
	}

	private NormalizedTradeCommand command(Long rawIngestId, String sourceKey, String aptDong) {
		return command(rawIngestId, sourceKey, aptDong, 84.93);
	}

	private NormalizedTradeCommand command(Long rawIngestId, String sourceKey, String aptDong, Double exclArea) {
		return new NormalizedTradeCommand(
			rawIngestId,
			501L,
			LocalDate.of(2025, 12, 1),
			125000L,
			12,
			exclArea,
			aptDong,
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

	private Long registryTradeId(String sourceKey) {
		Long tradeId = jdbcClient.sql("""
			SELECT COALESCE(trade_id, -1) AS trade_id
			FROM trade_source_key_registry
			WHERE source_key = :sourceKey
			""")
			.param("sourceKey", sourceKey)
			.query(Long.class)
			.single();
		return tradeId < 0 ? null : tradeId;
	}

	private List<String> activeAptDongs() {
		return jdbcClient.sql("""
			SELECT apt_dong
			FROM trade
			WHERE deleted_at IS NULL
			ORDER BY apt_dong
			""")
			.query(String.class)
			.list();
	}

	private long activeTradeCount() {
		return jdbcClient.sql("SELECT count(*) FROM trade WHERE deleted_at IS NULL")
			.query(Long.class)
			.single();
	}

	private long deletedTradeCount() {
		return jdbcClient.sql("SELECT count(*) FROM trade WHERE deleted_at IS NOT NULL")
			.query(Long.class)
			.single();
	}
}
