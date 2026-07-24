package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.raw.RawTradeIngestFailureQuery;
import com.home.application.ingest.raw.RawTradeIngestFailureSummary;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.domain.ingest.raw.RawTradeIngestStatus;
import com.home.infrastructure.persistence.ingest.raw.JdbcRawTradeIngestRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcRawTradeIngestRepositoryTest extends JdbcPostgresTestSupport {

    @Test
    @DisplayName("raw save는 날짜 원문을 먼저 보존하고 유효한 yy.MM.dd만 구조화 날짜로 저장한다")
    void savesRawAndParsedSourceDatesWithoutBlockingMalformedRows() {
        JdbcRawTradeIngestRepository repository = new JdbcRawTradeIngestRepository(jdbcClient);

        RawTradeIngestRecord valid = repository.save(RawTradeIngestRecord.received(
                "RTMS",
                "dated-source-key-1",
                "11680",
                "202607",
                1,
                "{\"rgstDate\":\"26.07.23\",\"cdealDay\":\"26.07.24\"}",
                "dated-payload-hash-1",
                null,
                "26.07.23",
                "26.07.24"));
        RawTradeIngestRecord invalid = repository.save(RawTradeIngestRecord.received(
                "RTMS",
                "dated-source-key-2",
                "11680",
                "202607",
                1,
                "{\"rgstDate\":\"26.02.30\"}",
                "dated-payload-hash-2",
                null,
                "26.02.30",
                null));

        assertThat(valid.registrationDateRaw()).isEqualTo("26.07.23");
        assertThat(valid.registrationDate()).isEqualTo(java.time.LocalDate.parse("2026-07-23"));
        assertThat(valid.cancellationDateRaw()).isEqualTo("26.07.24");
        assertThat(valid.cancellationDate()).isEqualTo(java.time.LocalDate.parse("2026-07-24"));
        assertThat(invalid.registrationDateRaw()).isEqualTo("26.02.30");
        assertThat(invalid.registrationDate()).isNull();
        assertThat(invalid.cancellationDateRaw()).isNull();
        assertThat(invalid.cancellationDate()).isNull();
        assertThat(rawCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("raw save는 id를 반환하고 failed match는 reason과 함께 queryable하다")
    void savesRawAndFindsMatchFailuresByStatus() {
        JdbcRawTradeIngestRepository repository = new JdbcRawTradeIngestRepository(jdbcClient);

        RawTradeIngestRecord saved = repository.save(RawTradeIngestRecord.received(
                "RTMS", "rtms-source-key-1", "11680", "202512", 1, "{\"aptSeq\":\"APT-404\"}", "payload-hash-1"));
        RawTradeIngestRecord failed = repository.updateStatus(
                saved.id(), RawTradeIngestStatus.MATCH_FAILED, "no complex matched aptSeq=APT-404");

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

    @Test
    @DisplayName("raw status 조회는 DB query 단계에서 limit을 적용한다")
    void findsRawByStatusWithDatabaseLimit() {
        JdbcRawTradeIngestRepository repository = new JdbcRawTradeIngestRepository(jdbcClient);
        mark(
                repository,
                raw(repository, "match-1", "11680", "202512", "{}"),
                RawTradeIngestStatus.MATCH_FAILED,
                "first");
        mark(
                repository,
                raw(repository, "match-2", "11680", "202512", "{}"),
                RawTradeIngestStatus.MATCH_FAILED,
                "second");
        mark(
                repository,
                raw(repository, "match-3", "11680", "202512", "{}"),
                RawTradeIngestStatus.MATCH_FAILED,
                "third");

        List<RawTradeIngestRecord> failures = repository.findByStatus(RawTradeIngestStatus.MATCH_FAILED, 2);

        assertThat(failures).hasSize(2);
        assertThat(failures).extracting(RawTradeIngestRecord::sourceKey).containsExactly("match-1", "match-2");
    }

    @Test
    @DisplayName("failure inspection은 source와 deal month별 safe read-only evidence만 summarize한다")
    void summarizesFailureEvidenceWithoutRawPayloadOrSourceKey() {
        JdbcRawTradeIngestRepository repository = new JdbcRawTradeIngestRepository(jdbcClient);
        mark(
                repository,
                raw(repository, "match-1", "11680", "202512", "{\"aptSeq\":\"APT-404\"}"),
                RawTradeIngestStatus.MATCH_FAILED,
                "no complex matched aptSeq=APT-404");
        mark(
                repository,
                raw(repository, "match-2", "11680", "202512", "{\"aptSeq\":\"APT-405\"}"),
                RawTradeIngestStatus.MATCH_FAILED,
                "no complex matched aptSeq=APT-404");
        mark(
                repository,
                raw(repository, "parse-1", "11680", "202512", "{\"dealAmount\":\"\"}"),
                RawTradeIngestStatus.PARSE_FAILED,
                "dealAmount is required");
        mark(
                repository,
                raw(repository, "duplicate-1", "11680", "202512", "{\"aptSeq\":\"APT-501\"}"),
                RawTradeIngestStatus.DUPLICATE,
                "duplicate source/source_key");
        mark(
                repository,
                raw(repository, "other-date", "11680", "202511", "{}"),
                RawTradeIngestStatus.MATCH_FAILED,
                "outside requested month");
        mark(
                repository,
                raw(repository, "other-source", "11680", "202512", "{}"),
                RawTradeIngestStatus.MATCH_FAILED,
                "other source");
        jdbcClient.sql("""
			UPDATE raw_trade_ingest
			SET source = 'MANUAL'
			WHERE source_key = 'other-source'
			""").update();

        List<RawTradeIngestFailureSummary> summaries =
                repository.summarizeFailures(RawTradeIngestFailureQuery.between("RTMS", "11680", "202512", "202512"));

        assertThat(summaries)
                .containsExactly(
                        new RawTradeIngestFailureSummary(
                                RawTradeIngestStatus.DUPLICATE,
                                "RTMS",
                                "11680",
                                "202512",
                                "duplicate source/source_key",
                                1),
                        new RawTradeIngestFailureSummary(
                                RawTradeIngestStatus.MATCH_FAILED,
                                "RTMS",
                                "11680",
                                "202512",
                                "no complex matched aptSeq=APT-404",
                                2),
                        new RawTradeIngestFailureSummary(
                                RawTradeIngestStatus.PARSE_FAILED,
                                "RTMS",
                                "11680",
                                "202512",
                                "dealAmount is required",
                                1));
        assertThat(Arrays.stream(RawTradeIngestFailureSummary.class.getRecordComponents())
                        .map(component -> component.getName()))
                .containsExactly("status", "source", "lawdCd", "dealYmd", "failureReason", "count")
                .doesNotContain("payload", "sourceKey");
    }

    @Test
    @DisplayName("processed raw source_key 존재 여부는 현재 row 이전 처리 완료 evidence만 중복 후보로 본다")
    void findsProcessedRawSourceKeyBeforeCurrentRowOnly() {
        JdbcRawTradeIngestRepository repository = new JdbcRawTradeIngestRepository(jdbcClient);
        RawTradeIngestRecord first = raw(repository, "repeat-source-key", "11680", "202512", "{}");
        RawTradeIngestRecord second = raw(repository, "repeat-source-key", "11680", "202512", "{}");

        assertThat(repository.existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
                        second.id(), "RTMS", "repeat-source-key", "payload-hash-repeat-source-key"))
                .isFalse();

        mark(repository, first, RawTradeIngestStatus.MATCH_FAILED, "no complex matched");

        assertThat(repository.existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
                        second.id(), "RTMS", "repeat-source-key", "payload-hash-repeat-source-key"))
                .isTrue();
        assertThat(repository.existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
                        second.id(), "RTMS", "repeat-source-key", "changed-payload-hash"))
                .isFalse();
        assertThat(repository.existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
                        first.id(), "RTMS", "repeat-source-key", "payload-hash-repeat-source-key"))
                .isFalse();
    }

    private RawTradeIngestRecord raw(
            JdbcRawTradeIngestRepository repository, String sourceKey, String lawdCd, String dealYmd, String payload) {
        return repository.save(RawTradeIngestRecord.received(
                "RTMS", sourceKey, lawdCd, dealYmd, 1, payload, "payload-hash-" + sourceKey));
    }

    private RawTradeIngestRecord mark(
            JdbcRawTradeIngestRepository repository,
            RawTradeIngestRecord record,
            RawTradeIngestStatus status,
            String failureReason) {
        return repository.updateStatus(record.id(), status, failureReason);
    }
}
