package com.home.migration;

import java.time.Duration;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

final class RegistryTradeDateBackfill {

	private final JdbcClient jdbcClient;
	private final TransactionTemplate transactions;

	RegistryTradeDateBackfill(JdbcClient jdbcClient, TransactionTemplate transactions) {
		this.jdbcClient = jdbcClient;
		this.transactions = transactions;
	}

	Result execute(int batchSize, long sleepMillis) {
		Instant startedAt = Instant.now();
		preflight();
		long baselineLinked = count("SELECT count(*) FROM trade_source_key_registry WHERE trade_id IS NOT NULL");
		long baselineCancellationOnly = count("SELECT count(*) FROM trade_source_key_registry WHERE trade_id IS NULL");
		System.out.printf("preflight linked=%d cancellationOnly=%d%n", baselineLinked, baselineCancellationOnly);

		long lastId = 0;
		long totalUpdated = 0;
		int batches = 0;
		while (true) {
			Long upperId = nextUpperId(lastId, batchSize);
			if (upperId == null) {
				break;
			}
			long lowerExclusive = lastId;
			Instant batchStartedAt = Instant.now();
			Integer updated = transactions.execute(status -> updateBatch(lowerExclusive, upperId));
			int safeUpdated = updated == null ? 0 : updated;
			totalUpdated += safeUpdated;
			batches++;
			System.out.printf("batch lowerExclusive=%d upperInclusive=%d updated=%d cumulative=%d elapsedMillis=%d%n",
				lowerExclusive,
				upperId,
				safeUpdated,
				totalUpdated,
				Duration.between(batchStartedAt, Instant.now()).toMillis());
			lastId = upperId;
			sleep(sleepMillis);
		}
		validateFinalState(baselineLinked, baselineCancellationOnly);
		return new Result(totalUpdated, batches, Duration.between(startedAt, Instant.now()).toMillis());
	}

	private void preflight() {
		if (relationExists("batch.BATCH_JOB_EXECUTION")) {
			long running = count("""
				SELECT count(*) FROM batch.BATCH_JOB_EXECUTION
				WHERE STATUS IN ('STARTING', 'STARTED', 'STOPPING')
				""");
			if (running != 0) {
				throw new MigrationOperationException("Backfill preflight failed: running Batch execution count=" + running);
			}
		}
		long duplicateTradeIds = count("""
			SELECT count(*) FROM (
			    SELECT id FROM trade GROUP BY id HAVING count(DISTINCT deal_date) > 1
			) duplicate_ids
			""");
		if (duplicateTradeIds != 0) {
			throw new MigrationOperationException("Backfill preflight failed: trade ids span multiple dates");
		}
		long orphans = count("""
			SELECT count(*)
			FROM trade_source_key_registry registry
			LEFT JOIN trade t ON t.id = registry.trade_id
			WHERE registry.trade_id IS NOT NULL AND t.id IS NULL
			""");
		if (orphans != 0) {
			throw new MigrationOperationException("Backfill preflight failed: orphan registry trade ids=" + orphans);
		}
	}

	private Long nextUpperId(long lastId, int batchSize) {
		return jdbcClient.sql("""
			SELECT max(id)
			FROM (
			    SELECT id
			    FROM trade_source_key_registry
			    WHERE id > :lastId
			      AND trade_id IS NOT NULL
			      AND trade_deal_date IS NULL
			    ORDER BY id
			    LIMIT :batchSize
			) batch_ids
			""")
			.param("lastId", lastId)
			.param("batchSize", batchSize)
			.query(Long.class)
			.optional()
			.orElse(null);
	}

	private int updateBatch(long lowerExclusive, long upperInclusive) {
		jdbcClient.sql("SET LOCAL max_parallel_workers_per_gather = 0").update();
		jdbcClient.sql("SET LOCAL lock_timeout = '2s'").update();
		jdbcClient.sql("SET LOCAL statement_timeout = '120s'").update();
		return jdbcClient.sql("""
			UPDATE trade_source_key_registry registry
			SET trade_deal_date = trade.deal_date
			FROM trade
			WHERE registry.id > :lowerExclusive
			  AND registry.id <= :upperInclusive
			  AND registry.trade_id = trade.id
			  AND registry.trade_id IS NOT NULL
			  AND registry.trade_deal_date IS NULL
			""")
			.param("lowerExclusive", lowerExclusive)
			.param("upperInclusive", upperInclusive)
			.update();
	}

	private void validateFinalState(long baselineLinked, long baselineCancellationOnly) {
		long linkedWithoutDate = count("""
			SELECT count(*) FROM trade_source_key_registry
			WHERE trade_id IS NOT NULL AND trade_deal_date IS NULL
			""");
		long dateWithoutLinked = count("""
			SELECT count(*) FROM trade_source_key_registry
			WHERE trade_id IS NULL AND trade_deal_date IS NOT NULL
			""");
		long orphanPairs = count("""
			SELECT count(*)
			FROM trade_source_key_registry registry
			LEFT JOIN trade t
			  ON t.id = registry.trade_id
			 AND t.deal_date = registry.trade_deal_date
			WHERE registry.trade_id IS NOT NULL AND t.id IS NULL
			""");
		long mismatch = count("""
			SELECT count(*)
			FROM trade_source_key_registry registry
			JOIN trade t ON t.id = registry.trade_id
			WHERE registry.trade_deal_date <> t.deal_date
			""");
		long linked = count("SELECT count(*) FROM trade_source_key_registry WHERE trade_id IS NOT NULL");
		long cancellationOnly = count("SELECT count(*) FROM trade_source_key_registry WHERE trade_id IS NULL");
		if (linkedWithoutDate != 0 || dateWithoutLinked != 0 || orphanPairs != 0 || mismatch != 0
			|| linked != baselineLinked || cancellationOnly != baselineCancellationOnly) {
			throw new MigrationOperationException("Backfill validation failed: linkedWithoutDate=%d dateWithoutLinked=%d orphanPairs=%d mismatch=%d"
				.formatted(linkedWithoutDate, dateWithoutLinked, orphanPairs, mismatch));
		}
	}

	private boolean relationExists(String relation) {
		return Boolean.TRUE.equals(jdbcClient.sql("SELECT to_regclass(:relation) IS NOT NULL")
			.param("relation", relation)
			.query(Boolean.class)
			.single());
	}

	private long count(String sql) {
		return jdbcClient.sql(sql).query(Long.class).single();
	}

	private void sleep(long sleepMillis) {
		if (sleepMillis == 0) {
			return;
		}
		try {
			Thread.sleep(sleepMillis);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new MigrationOperationException("Backfill interrupted", exception);
		}
	}

	record Result(long updated, int batches, long elapsedMillis) {
	}
}
