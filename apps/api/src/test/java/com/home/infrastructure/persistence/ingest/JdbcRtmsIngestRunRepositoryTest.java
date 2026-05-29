package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.RtmsIngestRunRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRtmsIngestRunRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("RTMS 수집 실행 summary는 raw payload나 source key 없이 count evidence로 저장된다")
	void savesRtmsIngestRunSummaryWithoutRawPayloadOrSourceKey() {
		JdbcRtmsIngestRunRepository repository = new JdbcRtmsIngestRunRepository(jdbcClient);
		RtmsIngestRunRecord record = RtmsIngestRunRecord.completed(
			"11680",
			"202512",
			2,
			new IngestResult(3, 3, 1, 1, 1, 0),
			Instant.parse("2026-05-29T00:00:00Z"),
			Instant.parse("2026-05-29T00:00:05Z")
		);

		RtmsIngestRunRecord saved = repository.save(record);

		assertThat(saved.id()).isNotNull();
		assertThat(saved.status()).isEqualTo("COMPLETED");
		assertThat(saved.pageCount()).isEqualTo(2);
		assertThat(saved.read()).isEqualTo(3);
		assertThat(saved.rawSaved()).isEqualTo(3);
		assertThat(saved.normalizedInserted()).isEqualTo(1);
		assertThat(saved.duplicateSkipped()).isEqualTo(1);
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
}
