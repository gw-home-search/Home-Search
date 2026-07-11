package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;

import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.run.RtmsIngestRunRecord;
import com.home.infrastructure.persistence.ingest.run.JdbcRtmsIngestRunRepository;
import com.home.domain.ingest.run.ExecutionCorrelationId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRtmsIngestRunRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("RTMS 수집 실행은 Batch execution correlation UUID를 JDBC round-trip으로 보존한다")
	void savesExecutionCorrelationId() {
		ExecutionCorrelationId correlationId = ExecutionCorrelationId.from(
			"123e4567-e89b-12d3-a456-426614174030"
		);
		RtmsIngestRunRecord record = RtmsIngestRunRecord.of(
			"11680",
			"202512",
			1,
			new IngestResult(1, 1, 1, 0, 0, 0),
			"COMPLETED",
			null,
			Instant.parse("2026-05-29T00:00:00Z"),
			Instant.parse("2026-05-29T00:00:05Z"),
			correlationId
		);

		RtmsIngestRunRecord saved = new JdbcRtmsIngestRunRepository(jdbcClient).save(record);

		assertThat(saved.executionCorrelationId()).isEqualTo(correlationId);
		assertThat(jdbcClient.sql("""
			SELECT execution_correlation_id::text FROM rtms_ingest_run WHERE id = :id
			""")
			.param("id", saved.id())
			.query(String.class)
			.single()).isEqualTo(correlationId.toString());
	}

	@Test
	@DisplayName("RTMS 수집 실행 summary는 raw payload나 source key 없이 count evidence로 저장된다")
	void savesRtmsIngestRunSummaryWithoutRawPayloadOrSourceKey() {
		JdbcRtmsIngestRunRepository repository = new JdbcRtmsIngestRunRepository(jdbcClient);
		RtmsIngestRunRecord record = RtmsIngestRunRecord.completed(
			"11680",
			"202512",
			2,
			new IngestResult(4, 4, 1, 1, 1, 1, 0),
			Instant.parse("2026-05-29T00:00:00Z"),
			Instant.parse("2026-05-29T00:00:05Z")
		);

		RtmsIngestRunRecord saved = repository.save(record);

		assertThat(saved.id()).isNotNull();
		assertThat(saved.status()).isEqualTo("COMPLETED");
		assertThat(saved.pageCount()).isEqualTo(2);
		assertThat(saved.read()).isEqualTo(4);
		assertThat(saved.rawSaved()).isEqualTo(4);
		assertThat(saved.normalizedInserted()).isEqualTo(1);
		assertThat(saved.duplicateSkipped()).isEqualTo(1);
		assertThat(saved.canceledSkipped()).isEqualTo(1);
		assertThat(saved.matchFailed()).isEqualTo(1);
		assertThat(saved.parseFailed()).isZero();
		assertThat(saved.failureReason()).isNull();
		assertThat(saved.createdAt()).isNotNull();
		assertThat(Arrays.stream(RtmsIngestRunRecord.class.getRecordComponents())
				.map(component -> component.getName()))
				.doesNotContain("payload", "sourceKey");
	}

	@Test
	@DisplayName("RTMS 수집 실행 실패는 FAILED status와 failure reason으로 저장된다")
	void savesFailedRtmsIngestRunSummaryWithFailureReason() {
		JdbcRtmsIngestRunRepository repository = new JdbcRtmsIngestRunRepository(jdbcClient);
		RtmsIngestRunRecord record = new RtmsIngestRunRecord(
			null,
			"11680",
			"202512",
			"FAILED",
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			"IllegalStateException: fetch failed",
			Instant.parse("2026-05-29T00:00:00Z"),
			Instant.parse("2026-05-29T00:00:05Z"),
			null
		);

		RtmsIngestRunRecord saved = repository.save(record);

		assertThat(saved.id()).isNotNull();
		assertThat(saved.status()).isEqualTo("FAILED");
		assertThat(saved.pageCount()).isZero();
		assertThat(saved.failureReason()).isEqualTo("IllegalStateException: fetch failed");
		assertThat(saved.createdAt()).isNotNull();
	}

	@Test
	@DisplayName("RTMS 수집 실행 일부 성공 후 실패는 PARTIAL status와 누적 count로 저장된다")
	void savesPartialRtmsIngestRunSummaryWithAccumulatedCounts() {
		JdbcRtmsIngestRunRepository repository = new JdbcRtmsIngestRunRepository(jdbcClient);
		RtmsIngestRunRecord record = RtmsIngestRunRecord.partiallyFailed(
			"11680",
			"202512",
			1,
			new IngestResult(3, 3, 2, 1, 0, 0),
			"IllegalStateException: temporary 503",
			Instant.parse("2026-05-29T00:00:00Z"),
			Instant.parse("2026-05-29T00:00:05Z")
		);

		RtmsIngestRunRecord saved = repository.save(record);

		assertThat(saved.id()).isNotNull();
		assertThat(saved.status()).isEqualTo("PARTIAL");
		assertThat(saved.pageCount()).isEqualTo(1);
		assertThat(saved.read()).isEqualTo(3);
		assertThat(saved.normalizedInserted()).isEqualTo(2);
		assertThat(saved.duplicateSkipped()).isEqualTo(1);
		assertThat(saved.failureReason()).isEqualTo("IllegalStateException: temporary 503");
	}
}
