package com.home.infrastructure.persistence.ingest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import com.home.application.ingest.RtmsIngestRunRecord;
import com.home.application.ingest.RtmsIngestRunRepository;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcRtmsIngestRunRepository implements RtmsIngestRunRepository {

	private final JdbcClient jdbcClient;

	public JdbcRtmsIngestRunRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public RtmsIngestRunRecord save(RtmsIngestRunRecord record) {
		Objects.requireNonNull(record, "record is required");
		return jdbcClient.sql("""
			INSERT INTO rtms_ingest_run (
			    lawd_cd,
			    deal_ymd,
			    status,
			    page_count,
			    read_count,
			    raw_saved_count,
			    normalized_inserted_count,
			    duplicate_skipped_count,
			    canceled_skipped_count,
			    match_failed_count,
			    parse_failed_count,
			    failure_reason,
			    started_at,
			    completed_at
			)
			VALUES (
			    :lawdCd,
			    :dealYmd,
			    :status,
			    :pageCount,
			    :readCount,
			    :rawSavedCount,
			    :normalizedInsertedCount,
			    :duplicateSkippedCount,
			    :canceledSkippedCount,
			    :matchFailedCount,
			    :parseFailedCount,
			    :failureReason,
			    :startedAt,
			    :completedAt
			)
			RETURNING *
			""")
			.param("lawdCd", record.lawdCd())
			.param("dealYmd", record.dealYmd())
			.param("status", record.status())
			.param("pageCount", record.pageCount())
			.param("readCount", record.read())
			.param("rawSavedCount", record.rawSaved())
			.param("normalizedInsertedCount", record.normalizedInserted())
			.param("duplicateSkippedCount", record.duplicateSkipped())
			.param("canceledSkippedCount", record.canceledSkipped())
			.param("matchFailedCount", record.matchFailed())
			.param("parseFailedCount", record.parseFailed())
			.param("failureReason", record.failureReason())
			.param("startedAt", offset(record.startedAt()))
			.param("completedAt", offset(record.completedAt()))
			.query(this::mapRecord)
			.single();
	}

	private RtmsIngestRunRecord mapRecord(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RtmsIngestRunRecord(
			resultSet.getLong("id"),
			resultSet.getString("lawd_cd"),
			resultSet.getString("deal_ymd"),
			resultSet.getString("status"),
			resultSet.getInt("page_count"),
			resultSet.getLong("read_count"),
			resultSet.getLong("raw_saved_count"),
			resultSet.getLong("normalized_inserted_count"),
			resultSet.getLong("duplicate_skipped_count"),
			resultSet.getLong("canceled_skipped_count"),
			resultSet.getLong("match_failed_count"),
			resultSet.getLong("parse_failed_count"),
			resultSet.getString("failure_reason"),
			instant(resultSet, "started_at"),
			instant(resultSet, "completed_at"),
			instant(resultSet, "created_at")
		);
	}

	private Instant instant(ResultSet resultSet, String column) throws SQLException {
		OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime offset(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
