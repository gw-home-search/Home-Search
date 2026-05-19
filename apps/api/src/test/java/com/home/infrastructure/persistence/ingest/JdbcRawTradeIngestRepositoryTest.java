package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.home.application.ingest.RawTradeIngestRecord;
import com.home.application.ingest.RawTradeIngestStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRawTradeIngestRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("raw save returns an id and failed matches are queryable with a reason")
	void savesRawAndFindsMatchFailuresByStatus() {
		JdbcRawTradeIngestRepository repository = new JdbcRawTradeIngestRepository(jdbcClient);

		RawTradeIngestRecord saved = repository.save(RawTradeIngestRecord.received(
			"RTMS",
			"rtms-source-key-1",
			"11680",
			"202512",
			1,
			"{\"aptSeq\":\"APT-404\"}",
			"payload-hash-1"
		));
		RawTradeIngestRecord failed = repository.updateStatus(
			saved.id(),
			RawTradeIngestStatus.MATCH_FAILED,
			"no complex matched aptSeq=APT-404"
		);

		assertThat(saved.id()).isNotNull();
		assertThat(failed.status()).isEqualTo(RawTradeIngestStatus.MATCH_FAILED);
		assertThat(failed.processedAt()).isNotNull();

		List<RawTradeIngestRecord> failures = repository.findByStatus(RawTradeIngestStatus.MATCH_FAILED);
		assertThat(failures).singleElement().satisfies(record -> {
			assertThat(record.id()).isEqualTo(saved.id());
			assertThat(record.failureReason()).isEqualTo("no complex matched aptSeq=APT-404");
			assertThat(record.sourceKey()).isEqualTo("rtms-source-key-1");
		});
	}
}
