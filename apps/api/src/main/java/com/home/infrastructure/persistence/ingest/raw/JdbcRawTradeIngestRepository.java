package com.home.infrastructure.persistence.ingest.raw;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import com.home.application.ingest.raw.RawTradeIngestFailureQuery;
import com.home.application.ingest.raw.RawTradeIngestFailureSummary;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.domain.ingest.raw.RawTradeIngestStatus;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * raw_trade_ingest table에 원천 수집 evidence를 저장하고 status별 조회를 제공하는 JDBC adapter입니다.
 */
public class JdbcRawTradeIngestRepository implements RawTradeIngestRepository {

	private final JdbcClient jdbcClient;

	public JdbcRawTradeIngestRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public RawTradeIngestRecord save(RawTradeIngestRecord record) {
		return jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status,
			    failure_reason,
			    created_at,
			    processed_at
			)
			VALUES (
			    :source,
			    :sourceKey,
			    :lawdCd,
			    :dealYmd,
			    :pageNo,
			    :payload,
			    :payloadHash,
			    :status,
			    :failureReason,
			    :createdAt,
			    :processedAt
			)
			RETURNING *
			""")
			.param("source", record.source())
			.param("sourceKey", record.sourceKey())
			.param("lawdCd", record.lawdCd())
			.param("dealYmd", record.dealYmd())
			.param("pageNo", record.pageNo())
			.param("payload", record.payload())
			.param("payloadHash", record.payloadHash())
			.param("status", record.status().name())
			.param("failureReason", record.failureReason())
			.param("createdAt", offset(record.createdAt()))
			.param("processedAt", offset(record.processedAt()))
			.query(this::mapRecord)
			.single();
	}

	@Override
	public boolean existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
		Long rawIngestId,
		String source,
		String sourceKey,
		String payloadHash
	) {
		return Boolean.TRUE.equals(jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM raw_trade_ingest
			    WHERE id < :rawIngestId
			      AND source = :source
			      AND source_key = :sourceKey
			      AND payload_hash = :payloadHash
			      AND status <> 'RECEIVED'
			)
			""")
			.param("rawIngestId", rawIngestId)
			.param("source", source)
			.param("sourceKey", sourceKey)
			.param("payloadHash", payloadHash)
			.query(Boolean.class)
			.single());
	}

	@Override
	public RawTradeIngestRecord updateStatus(Long id, RawTradeIngestStatus status, String failureReason) {
		return jdbcClient.sql("""
			UPDATE raw_trade_ingest
			SET status = :status,
			    failure_reason = :failureReason,
			    processed_at = :processedAt
			WHERE id = :id
			RETURNING *
			""")
			.param("id", id)
			.param("status", status.name())
			.param("failureReason", failureReason)
			.param("processedAt", offset(Instant.now()))
			.query(this::mapRecord)
			.single();
	}

	@Override
	public List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status) {
		return jdbcClient.sql("""
			SELECT *
			FROM raw_trade_ingest
			WHERE status = :status
			ORDER BY id
			""")
			.param("status", status.name())
			.query(this::mapRecord)
			.list();
	}

	@Override
	public List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status, int limit) {
		if (limit <= 0) {
			return List.of();
		}
		return jdbcClient.sql("""
			SELECT *
			FROM raw_trade_ingest
			WHERE status = :status
			ORDER BY id
			LIMIT :limit
			""")
			.param("status", status.name())
			.param("limit", limit)
			.query(this::mapRecord)
			.list();
	}

	@Override
	public List<RawTradeIngestFailureSummary> summarizeFailures(RawTradeIngestFailureQuery query) {
		Objects.requireNonNull(query, "query is required");
		return jdbcClient.sql("""
			SELECT
			    status,
			    source,
			    lawd_cd,
			    deal_ymd,
			    failure_reason,
			    count(*) AS record_count
			FROM raw_trade_ingest
			WHERE status IN (:statuses)
			  AND (:source IS NULL OR source = :source)
			  AND (:lawdCd IS NULL OR lawd_cd = :lawdCd)
			  AND (:dealYmdFrom IS NULL OR deal_ymd >= :dealYmdFrom)
			  AND (:dealYmdTo IS NULL OR deal_ymd <= :dealYmdTo)
			GROUP BY status, source, lawd_cd, deal_ymd, failure_reason
			ORDER BY status, source, lawd_cd, deal_ymd, failure_reason NULLS LAST
			""")
			.param("statuses", query.statusNames())
			.param("source", query.source())
			.param("lawdCd", query.lawdCd())
			.param("dealYmdFrom", query.dealYmdFrom())
			.param("dealYmdTo", query.dealYmdTo())
			.query(this::mapFailureSummary)
			.list();
	}

	private RawTradeIngestRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RawTradeIngestRecord(
			resultSet.getLong("id"),
			resultSet.getString("source"),
			resultSet.getString("source_key"),
			resultSet.getString("lawd_cd"),
			resultSet.getString("deal_ymd"),
			integerOrNull(resultSet, "page_no"),
			resultSet.getString("payload"),
			resultSet.getString("payload_hash"),
			RawTradeIngestStatus.valueOf(resultSet.getString("status")),
			resultSet.getString("failure_reason"),
			instantOrNull(resultSet, "created_at"),
			instantOrNull(resultSet, "processed_at")
		);
	}

	private RawTradeIngestFailureSummary mapFailureSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RawTradeIngestFailureSummary(
			RawTradeIngestStatus.valueOf(resultSet.getString("status")),
			resultSet.getString("source"),
			resultSet.getString("lawd_cd"),
			resultSet.getString("deal_ymd"),
			resultSet.getString("failure_reason"),
			resultSet.getLong("record_count")
		);
	}

	private Integer integerOrNull(ResultSet resultSet, String column) throws SQLException {
		int value = resultSet.getInt(column);
		return resultSet.wasNull() ? null : value;
	}

	private Instant instantOrNull(ResultSet resultSet, String column) throws SQLException {
		OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
