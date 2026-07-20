package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.buildingregister.BuildingRegisterRawPageReceiptCommand;
import com.home.application.ingest.buildingregister.BuildingRegisterRecordSnapshotCommand;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import com.home.domain.complex.buildingregister.BuildingRegisterRawPageStatus;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingRegisterRawPageRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcBuildingRegisterRawPageRepositoryTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174110");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174111");
    private JdbcBuildingRegisterRawPageRepository repository;
    private long snapshotId;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                    (collection_id,mode,strategy,to_complex_id,status)
                    VALUES (:id,'missing','ADAPTIVE',1000,'COLLECTING')
                    """).param("id", COLLECTION_ID).update();
        snapshotId =
                jdbcClient.sql("""
                    INSERT INTO building_register_endpoint_snapshot
                    (collection_id,pnu,endpoint,run_date,page_size,attempt_no,status)
                    VALUES (:id,'1168010300101400001','TITLE',DATE '2026-07-20',100,1,'ACTIVE')
                    RETURNING id
                    """).param("id", COLLECTION_ID).query(Long.class).single();
        repository = new JdbcBuildingRegisterRawPageRepository(jdbcClient, transactionTemplate);
    }

    @Test
    void commitsRawPageBeforeNormalizedRecordsAndFinalizesParsedState() {
        long rawPageId = repository.receive(receipt("{\"response\":{}}"));

        assertThat(rawStatus(rawPageId)).isEqualTo("RECEIVED");
        assertThat(recordCount(rawPageId)).isZero();

        repository.complete(rawPageId, BuildingRegisterRawPageStatus.PARSED, List.of(record()));

        assertThat(rawStatus(rawPageId)).isEqualTo("PARSED");
        assertThat(recordCount(rawPageId)).isOne();
        assertThat(jdbcClient
                        .sql("SELECT vl_rat_estm_tot_area FROM building_register_record_snapshot WHERE raw_page_id=:id")
                        .param("id", rawPageId)
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("800.123456");
    }

    @Test
    void sameAttemptReceiptIsIdempotentWithoutOverwritingRawBody() {
        long first = repository.receive(receipt("{\"first\":true}"));
        long second = repository.receive(receipt("{\"second\":true}"));

        assertThat(second).isEqualTo(first);
        assertThat(jdbcClient
                        .sql("SELECT response_body FROM building_register_raw_page WHERE id=:id")
                        .param("id", first)
                        .query(String.class)
                        .single())
                .isEqualTo("{\"first\":true}");
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM building_register_raw_page WHERE endpoint_snapshot_id=:id")
                        .param("id", snapshotId)
                        .query(Integer.class)
                        .single())
                .isOne();
    }

    @Test
    void parseFailureKeepsRawBodyAndCanBeReplayedWithoutAnotherReceipt() {
        long rawPageId = repository.receive(receipt("{\"recoverable\":true}"));
        repository.complete(rawPageId, BuildingRegisterRawPageStatus.PARSE_FAILED, List.of());

        assertThat(rawStatus(rawPageId)).isEqualTo("PARSE_FAILED");
        assertThat(repository.body(rawPageId)).contains("recoverable");

        repository.complete(rawPageId, BuildingRegisterRawPageStatus.PARSED, List.of(record()));

        assertThat(rawStatus(rawPageId)).isEqualTo("PARSED");
        assertThat(recordCount(rawPageId)).isOne();
    }

    private BuildingRegisterRawPageReceiptCommand receipt(String body) {
        return new BuildingRegisterRawPageReceiptCommand(
                snapshotId, REQUEST_ID, 1, 1, body, "a".repeat(64), body.getBytes().length, 200, "00");
    }

    private BuildingRegisterRecordSnapshotCommand record() {
        return new BuildingRegisterRecordSnapshotCommand(
                0,
                "1168010300101400001",
                BuildingRegisterEndpoint.TITLE,
                "TITLE-1",
                "ROOT-1",
                "1",
                "3",
                "0",
                "0",
                "Sample",
                "101",
                "02000",
                new BigDecimal("1000.123456"),
                new BigDecimal("200.123456"),
                new BigDecimal("999.123456"),
                new BigDecimal("800.123456"),
                new BigDecimal("20.12345678"),
                new BigDecimal("80.12345678"),
                2,
                1,
                740,
                LocalDate.of(2015, 3, 20),
                LocalDate.of(2026, 7, 20));
    }

    private String rawStatus(long rawPageId) {
        return jdbcClient
                .sql("SELECT status FROM building_register_raw_page WHERE id=:id")
                .param("id", rawPageId)
                .query(String.class)
                .single();
    }

    private int recordCount(long rawPageId) {
        return jdbcClient
                .sql("SELECT count(*) FROM building_register_record_snapshot WHERE raw_page_id=:id")
                .param("id", rawPageId)
                .query(Integer.class)
                .single();
    }
}
