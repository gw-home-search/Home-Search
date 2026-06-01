package com.home.infrastructure.persistence.ingest;

import java.util.List;
import java.util.Objects;

import com.home.application.ingest.RawIngestReconciliationCandidate;
import com.home.application.ingest.RawIngestReconciliationRepository;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcRawIngestReconciliationRepository implements RawIngestReconciliationRepository {

	private final JdbcClient jdbcClient;

	public JdbcRawIngestReconciliationRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<RawIngestReconciliationCandidate> findReceivedRowsLinkedToActiveTrade(int limit) {
		if (limit <= 0) {
			return List.of();
		}
		return jdbcClient.sql("""
			SELECT
			    raw.id AS raw_ingest_id,
			    registry.trade_id
			FROM raw_trade_ingest raw
			JOIN trade_source_key_registry registry
			  ON registry.source = raw.source
			 AND registry.source_key = raw.source_key
			JOIN trade t
			  ON t.id = registry.trade_id
			 AND t.deleted_at IS NULL
			WHERE raw.status = 'RECEIVED'
			ORDER BY raw.id
			LIMIT :limit
			""")
			.param("limit", limit)
			.query((resultSet, rowNumber) -> new RawIngestReconciliationCandidate(
				resultSet.getLong("raw_ingest_id"),
				resultSet.getLong("trade_id")
			))
			.list();
	}
}
