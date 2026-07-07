package com.home.infrastructure.persistence.ingest.run;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.home.application.ingest.run.RtmsIngestRunRecord;
import com.home.application.ingest.run.RtmsIngestRunReport;
import com.home.application.ingest.run.RtmsIngestRunReportQuery;
import com.home.application.ingest.run.RtmsIngestRunReportRepository;
import com.home.application.ingest.run.RtmsIngestRunReportTotals;
import com.home.domain.ingest.run.RtmsIngestRunStatus;
import com.home.application.ingest.run.RtmsIngestRunStatusSummary;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * JDBC adapter that summarizes rtms_ingest_run outcomes without raw payload or source_key.
 */
public class JdbcRtmsIngestRunReportRepository implements RtmsIngestRunReportRepository {

	private final JdbcClient jdbcClient;

	public JdbcRtmsIngestRunReportRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public RtmsIngestRunReport summarize(RtmsIngestRunReportQuery query) {
		Objects.requireNonNull(query, "query is required");
		return new RtmsIngestRunReport(
			findTotals(query),
			findStatusSummaries(query),
			findRecentRuns(query)
		);
	}

	private RtmsIngestRunReportTotals findTotals(RtmsIngestRunReportQuery query) {
		return baseQuery("""
			SELECT
			    count(*)::bigint AS run_count,
			    COALESCE(sum(page_count), 0)::bigint AS page_count,
			    COALESCE(sum(read_count), 0)::bigint AS read_count,
			    COALESCE(sum(raw_saved_count), 0)::bigint AS raw_saved_count,
			    COALESCE(sum(normalized_inserted_count), 0)::bigint AS normalized_inserted_count,
			    COALESCE(sum(duplicate_skipped_count), 0)::bigint AS duplicate_skipped_count,
			    COALESCE(sum(canceled_skipped_count), 0)::bigint AS canceled_skipped_count,
			    COALESCE(sum(match_failed_count), 0)::bigint AS match_failed_count,
			    COALESCE(sum(parse_failed_count), 0)::bigint AS parse_failed_count
			FROM rtms_ingest_run
			WHERE status IN (:statuses)
			  AND (:lawdCd IS NULL OR lawd_cd = :lawdCd)
			  AND (:dealYmdFrom IS NULL OR deal_ymd >= :dealYmdFrom)
			  AND (:dealYmdTo IS NULL OR deal_ymd <= :dealYmdTo)
			""", query)
			.query(this::mapTotals)
			.single();
	}

	private List<RtmsIngestRunStatusSummary> findStatusSummaries(RtmsIngestRunReportQuery query) {
		Map<RtmsIngestRunStatus, Long> counts = baseQuery("""
			SELECT status, count(*)::bigint AS run_count
			FROM rtms_ingest_run
			WHERE status IN (:statuses)
			  AND (:lawdCd IS NULL OR lawd_cd = :lawdCd)
			  AND (:dealYmdFrom IS NULL OR deal_ymd >= :dealYmdFrom)
			  AND (:dealYmdTo IS NULL OR deal_ymd <= :dealYmdTo)
			GROUP BY status
			ORDER BY CASE status
			    WHEN 'COMPLETED' THEN 1
			    WHEN 'PARTIAL' THEN 2
			    WHEN 'FAILED' THEN 3
			    ELSE 4
			END
			""", query)
			.query(this::mapStatusSummary)
			.list()
			.stream()
			.collect(Collectors.toMap(RtmsIngestRunStatusSummary::status, RtmsIngestRunStatusSummary::runCount));
		return query.statuses()
			.stream()
			.map(status -> new RtmsIngestRunStatusSummary(status, counts.getOrDefault(status, 0L)))
			.toList();
	}

	private List<RtmsIngestRunRecord> findRecentRuns(RtmsIngestRunReportQuery query) {
		return baseQuery("""
			SELECT *
			FROM rtms_ingest_run
			WHERE status IN (:statuses)
			  AND (:lawdCd IS NULL OR lawd_cd = :lawdCd)
			  AND (:dealYmdFrom IS NULL OR deal_ymd >= :dealYmdFrom)
			  AND (:dealYmdTo IS NULL OR deal_ymd <= :dealYmdTo)
			ORDER BY completed_at DESC, id DESC
			LIMIT :recentRunLimit
			""", query)
			.param("recentRunLimit", query.recentRunLimit())
			.query(this::mapRecord)
			.list();
	}

	private JdbcClient.StatementSpec baseQuery(String sql, RtmsIngestRunReportQuery query) {
		return jdbcClient.sql(sql)
			.param("statuses", query.statusNames())
			.param("lawdCd", query.lawdCd())
			.param("dealYmdFrom", query.dealYmdFrom())
			.param("dealYmdTo", query.dealYmdTo());
	}

	private RtmsIngestRunReportTotals mapTotals(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RtmsIngestRunReportTotals(
			resultSet.getLong("run_count"),
			resultSet.getLong("page_count"),
			resultSet.getLong("read_count"),
			resultSet.getLong("raw_saved_count"),
			resultSet.getLong("normalized_inserted_count"),
			resultSet.getLong("duplicate_skipped_count"),
			resultSet.getLong("canceled_skipped_count"),
			resultSet.getLong("match_failed_count"),
			resultSet.getLong("parse_failed_count")
		);
	}

	private RtmsIngestRunStatusSummary mapStatusSummary(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RtmsIngestRunStatusSummary(
			RtmsIngestRunStatus.valueOf(resultSet.getString("status")),
			resultSet.getLong("run_count")
		);
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
}
