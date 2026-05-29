package com.home.infrastructure.persistence.ingest;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.NormalizedTradeCommand;
import com.home.application.ingest.NormalizedTradeRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * source_key registry와 fallback identity를 함께 사용해 normalized trade 중복 생성을 막는 JDBC adapter입니다.
 */
public class JdbcNormalizedTradeRepository implements NormalizedTradeRepository {

	private final JdbcClient jdbcClient;
	private final TransactionTemplate transactionTemplate;

	public JdbcNormalizedTradeRepository(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
		this.transactionTemplate = Objects.requireNonNull(transactionTemplate);
	}

	@Override
	public boolean existsBySourceAndSourceKey(String source, String sourceKey) {
		return Boolean.TRUE.equals(jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM trade_source_key_registry
			    WHERE source = :source
			      AND source_key = :sourceKey
			)
			""")
			.param("source", source)
			.param("sourceKey", sourceKey)
			.query(Boolean.class)
			.single());
	}

	@Override
	public boolean insertIfAbsent(NormalizedTradeCommand command) {
		return Boolean.TRUE.equals(transactionTemplate.execute(status -> insertInTransaction(command)));
	}

	@Override
	public boolean cancelBySourceAndSourceKey(String source, String sourceKey, Long rawIngestId) {
		return Boolean.TRUE.equals(transactionTemplate.execute(status -> cancelInTransaction(source, sourceKey,
			rawIngestId)));
	}

	private boolean insertInTransaction(NormalizedTradeCommand command) {
		Optional<Long> registryId = insertRegistry(command);
		if (registryId.isEmpty()) {
			return false;
		}

		Optional<Long> tradeId = insertTrade(command);
		if (tradeId.isEmpty()) {
			Long existingTradeId = findExistingTradeId(command)
				.orElseThrow(() -> new IllegalStateException("fallback duplicate trade id was not found"));
			attachTrade(registryId.get(), existingTradeId);
			return false;
		}

		attachTrade(registryId.get(), tradeId.get());
		return true;
	}

	private Optional<Long> insertRegistry(NormalizedTradeCommand command) {
		return insertRegistry(command.source(), command.sourceKey(), command.rawIngestId());
	}

	private Optional<Long> insertRegistry(String source, String sourceKey, Long rawIngestId) {
		return jdbcClient.sql("""
			INSERT INTO trade_source_key_registry (source, source_key, raw_ingest_id)
			VALUES (:source, :sourceKey, :rawIngestId)
			ON CONFLICT (source, source_key) DO NOTHING
			RETURNING id
			""")
			.param("source", source)
			.param("sourceKey", sourceKey)
			.param("rawIngestId", rawIngestId)
			.query(Long.class)
			.optional();
	}

	private boolean cancelInTransaction(String source, String sourceKey, Long rawIngestId) {
		insertRegistry(source, sourceKey, rawIngestId);
		int updated = jdbcClient.sql("""
			UPDATE trade t
			SET deleted_at = now(),
			    updated_at = now()
			FROM trade_source_key_registry r
			WHERE r.trade_id = t.id
			  AND r.source = :source
			  AND r.source_key = :sourceKey
			  AND t.deleted_at IS NULL
			""")
			.param("source", source)
			.param("sourceKey", sourceKey)
			.update();
		return updated > 0;
	}

	private Optional<Long> insertTrade(NormalizedTradeCommand command) {
		return jdbcClient.sql("""
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
			    :rawIngestId,
			    :complexId,
			    :dealDate,
			    :dealAmount,
			    :floor,
			    :exclArea,
			    :aptDong,
			    :source,
			    :sourceKey,
			    :complexPk,
			    :aptSeq
			)
			ON CONFLICT DO NOTHING
			RETURNING id
			""")
			.param("rawIngestId", command.rawIngestId())
			.param("complexId", command.complexId())
			.param("dealDate", command.dealDate())
			.param("dealAmount", command.dealAmount())
			.param("floor", command.floor())
			.param("exclArea", decimalOrNull(command.exclArea()))
			.param("aptDong", command.aptDong())
			.param("source", command.source())
			.param("sourceKey", command.sourceKey())
			.param("complexPk", command.complexPk())
			.param("aptSeq", command.aptSeq())
			.query(Long.class)
			.optional();
	}

	private Optional<Long> findExistingTradeId(NormalizedTradeCommand command) {
		return jdbcClient.sql("""
			SELECT id
			FROM trade
			WHERE complex_id = :complexId
			  AND deal_date = :dealDate
			  AND floor IS NOT DISTINCT FROM :floor
			  AND excl_area IS NOT DISTINCT FROM :exclArea
			  AND deal_amount = :dealAmount
			ORDER BY id
			LIMIT 1
			""")
			.param("complexId", command.complexId())
			.param("dealDate", command.dealDate())
			.param("floor", command.floor())
			.param("exclArea", decimalOrNull(command.exclArea()))
			.param("dealAmount", command.dealAmount())
			.query(Long.class)
			.optional();
	}

	private void attachTrade(Long registryId, Long tradeId) {
		jdbcClient.sql("""
			UPDATE trade_source_key_registry
			SET trade_id = :tradeId
			WHERE id = :registryId
			""")
			.param("tradeId", tradeId)
			.param("registryId", registryId)
			.update();
	}

	private BigDecimal decimalOrNull(Double value) {
		return value == null ? null : BigDecimal.valueOf(value);
	}
}
