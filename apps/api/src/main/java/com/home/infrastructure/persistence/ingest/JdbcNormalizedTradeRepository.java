package com.home.infrastructure.persistence.ingest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.home.application.ingest.NormalizedTradeCommand;
import com.home.application.ingest.NormalizedTradeRepository;
import com.home.application.ingest.TradeExclAreaNormalizer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * source_key registry와 fallback identity를 함께 사용해 normalized trade 중복 생성을 막는 JDBC adapter입니다.
 */
public class JdbcNormalizedTradeRepository implements NormalizedTradeRepository {

	private static final int FALLBACK_IDENTITY_LOCK_NAMESPACE = 0x48534D45;

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

		lockFallbackIdentity(command);
		FallbackMatch existingTrade = findFallbackMatch(command);
		if (existingTrade.tradeId().isPresent()) {
			attachTrade(registryId.get(), existingTrade.tradeId().get());
			return false;
		}
		if (existingTrade.ambiguous()) {
			return false;
		}

		Optional<Long> tradeId = insertTrade(command);
		if (tradeId.isEmpty()) {
			FallbackMatch conflictedTrade = findFallbackMatch(command);
			if (conflictedTrade.tradeId().isPresent()) {
				attachTrade(registryId.get(), conflictedTrade.tradeId().get());
			}
			else if (!conflictedTrade.ambiguous()) {
				throw new IllegalStateException("fallback duplicate trade id was not found");
			}
			return false;
		}

		attachTrade(registryId.get(), tradeId.get());
		return true;
	}

	private void lockFallbackIdentity(NormalizedTradeCommand command) {
		jdbcClient.sql("SELECT pg_advisory_xact_lock(:namespace, :lockKey)")
			.param("namespace", FALLBACK_IDENTITY_LOCK_NAMESPACE)
			.param("lockKey", fallbackIdentityLockKey(command))
			.query((resultSet, rowNumber) -> 0)
			.single();
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

	private FallbackMatch findFallbackMatch(NormalizedTradeCommand command) {
		if (command.aptDong() == null) {
			List<Long> candidates = findFallbackCandidateIds(command);
			if (candidates.size() == 1) {
				return FallbackMatch.matched(candidates.get(0));
			}
			return candidates.isEmpty() ? FallbackMatch.none() : FallbackMatch.ambiguousMatch();
		}

		Optional<Long> exactAptDong = findExistingTradeIdByAptDong(command);
		if (exactAptDong.isPresent()) {
			return FallbackMatch.matched(exactAptDong.get());
		}
		return findExistingTradeIdWithMissingAptDong(command)
			.map(FallbackMatch::matched)
			.orElseGet(FallbackMatch::none);
	}

	private Optional<Long> findExistingTradeIdByAptDong(NormalizedTradeCommand command) {
		return jdbcClient.sql("""
			SELECT id
			FROM trade
			WHERE complex_id = :complexId
			  AND deal_date = :dealDate
			  AND floor IS NOT DISTINCT FROM :floor
			  AND excl_area IS NOT DISTINCT FROM :exclArea
			  AND deal_amount = :dealAmount
			  AND apt_dong = :aptDong
			ORDER BY id
			LIMIT 1
			""")
			.param("complexId", command.complexId())
			.param("dealDate", command.dealDate())
			.param("floor", command.floor())
			.param("exclArea", decimalOrNull(command.exclArea()))
			.param("dealAmount", command.dealAmount())
			.param("aptDong", command.aptDong())
			.query(Long.class)
			.optional();
	}

	private Optional<Long> findExistingTradeIdWithMissingAptDong(NormalizedTradeCommand command) {
		return jdbcClient.sql("""
			SELECT id
			FROM trade
			WHERE complex_id = :complexId
			  AND deal_date = :dealDate
			  AND floor IS NOT DISTINCT FROM :floor
			  AND excl_area IS NOT DISTINCT FROM :exclArea
			  AND deal_amount = :dealAmount
			  AND apt_dong IS NULL
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

	private List<Long> findFallbackCandidateIds(NormalizedTradeCommand command) {
		return jdbcClient.sql("""
			SELECT id
			FROM trade
			WHERE complex_id = :complexId
			  AND deal_date = :dealDate
			  AND floor IS NOT DISTINCT FROM :floor
			  AND excl_area IS NOT DISTINCT FROM :exclArea
			  AND deal_amount = :dealAmount
			ORDER BY id
			LIMIT 2
			""")
			.param("complexId", command.complexId())
			.param("dealDate", command.dealDate())
			.param("floor", command.floor())
			.param("exclArea", decimalOrNull(command.exclArea()))
			.param("dealAmount", command.dealAmount())
			.query(Long.class)
			.list();
	}

	private int fallbackIdentityLockKey(NormalizedTradeCommand command) {
		BigDecimal exclArea = decimalOrNull(command.exclArea());
		String material = "%s|%s|%s|%s|%s".formatted(
			command.complexId(),
			command.dealDate(),
			command.floor(),
			exclArea == null ? "" : exclArea.stripTrailingZeros().toPlainString(),
			command.dealAmount()
		);
		return material.hashCode();
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
		return TradeExclAreaNormalizer.normalize(value);
	}

	private record FallbackMatch(
		Optional<Long> tradeId,
		boolean ambiguous
	) {

		private static FallbackMatch matched(Long tradeId) {
			return new FallbackMatch(Optional.of(tradeId), false);
		}

		private static FallbackMatch none() {
			return new FallbackMatch(Optional.empty(), false);
		}

		private static FallbackMatch ambiguousMatch() {
			return new FallbackMatch(Optional.empty(), true);
		}
	}
}
