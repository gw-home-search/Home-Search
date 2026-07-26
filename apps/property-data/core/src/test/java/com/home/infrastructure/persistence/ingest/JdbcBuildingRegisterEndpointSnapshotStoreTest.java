package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.buildingregister.BuildingRegisterCollectionStatus;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingRegisterEndpointSnapshotStore;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcBuildingRegisterEndpointSnapshotStoreTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174130");
    private static final UUID REPAIR_COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174131");
    private static final String PNU = "1168010300101400001";
    private final LocalDate runDate = LocalDate.of(2026, 7, 20);
    private JdbcBuildingRegisterEndpointSnapshotStore store;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                    (collection_id,mode,strategy,to_complex_id,status)
                    VALUES (:id,'missing','ADAPTIVE',1000,'COLLECTING')
                    """).param("id", COLLECTION_ID).update();
        store = new JdbcBuildingRegisterEndpointSnapshotStore(jdbcClient, transactionTemplate);
    }

    @Test
    @DisplayName("건축물대장 endpoint snapshot 저장을 검증한다")
    void resumesSameActiveSnapshotButStartsNewAttemptAfterOversizedAbandonment() {
        var first = store.open(COLLECTION_ID, PNU, BuildingRegisterEndpoint.TITLE, runDate, 100);
        var resumed = store.open(COLLECTION_ID, PNU, BuildingRegisterEndpoint.TITLE, runDate, 100);

        store.abandonOversized(first.id(), 100, false);
        var reduced = store.open(COLLECTION_ID, PNU, BuildingRegisterEndpoint.TITLE, runDate, 50);

        assertThat(resumed.id()).isEqualTo(first.id());
        assertThat(reduced.id()).isNotEqualTo(first.id());
        assertThat(reduced.attemptNo()).isEqualTo(2);
        assertThat(jdbcClient
                        .sql("SELECT status FROM building_register_endpoint_snapshot WHERE id=:id")
                        .param("id", first.id())
                        .query(String.class)
                        .single())
                .isEqualTo("ABANDONED_OVERSIZED");
    }

    @Test
    @DisplayName("건축물대장 endpoint snapshot 저장을 검증한다")
    void mapsCollectedSnapshotToParsedOrEmptyTerminalState() {
        var parsed = store.open(COLLECTION_ID, PNU, BuildingRegisterEndpoint.RECAP_TITLE, runDate, 100);
        store.complete(parsed.id(), 3, BuildingRegisterCollectionStatus.COLLECTED);
        var empty = store.open(COLLECTION_ID, PNU, BuildingRegisterEndpoint.BASIC_OVERVIEW, runDate, 100);
        store.complete(empty.id(), 0, BuildingRegisterCollectionStatus.COLLECTED);

        assertThat(status(parsed.id())).isEqualTo("PARSED");
        assertThat(status(empty.id())).isEqualTo("EMPTY");
    }

    @Test
    @DisplayName("완료 여부와 무관하게 같은 campaign의 endpoint snapshot을 다음 runDate에서 재개한다")
    void resumesCompletedAndIncompleteSnapshotsAcrossRunDates() {
        var completed = store.open(COLLECTION_ID, PNU, BuildingRegisterEndpoint.RECAP_TITLE, runDate, 100);
        store.complete(completed.id(), 3, BuildingRegisterCollectionStatus.COLLECTED);
        var incomplete = store.open(COLLECTION_ID, PNU, BuildingRegisterEndpoint.TITLE, runDate, 100);

        LocalDate nextRunDate = runDate.plusDays(1);
        var reused = store.open(COLLECTION_ID, PNU, BuildingRegisterEndpoint.RECAP_TITLE, nextRunDate, 100);
        var restarted = store.open(COLLECTION_ID, PNU, BuildingRegisterEndpoint.TITLE, nextRunDate, 100);

        assertThat(reused.id()).isEqualTo(completed.id());
        assertThat(restarted.id()).isEqualTo(incomplete.id());
    }

    @Test
    @DisplayName("repair campaign은 source의 완료 EMPTY page를 raw body와 함께 복사해 재호출하지 않는다")
    void clonesCompletedSourcePageForRepairResume() {
        var source = store.open(COLLECTION_ID, PNU, BuildingRegisterEndpoint.BASIC_OVERVIEW, runDate, 100);
        jdbcClient.sql("""
                    INSERT INTO building_register_raw_page(
                      endpoint_snapshot_id,request_id,page_no,attempt_no,status,response_body,
                      body_sha256,byte_count,http_status,provider_status,finalized_at)
                    VALUES (:snapshot,'123e4567-e89b-12d3-a456-426614174139',1,1,'EMPTY','{}',
                      repeat('a',64),2,200,'00',now())
                    """).param("snapshot", source.id()).update();
        store.complete(source.id(), 0, BuildingRegisterCollectionStatus.COLLECTED);
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign(
                      collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,selection_seed,sample_size)
                    VALUES (:id,'profile','COMPARE_RECAP_TITLE',1000,'COLLECTING','PROFILE_DISCOVERY',
                      'NATIONWIDE_STAGING','repair-test',1)
                    """).param("id", REPAIR_COLLECTION_ID).update();
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_repair_run(
                      collection_id,source_collection_id,request_id,run_date,repair_policy_version,
                      max_requests,parallelism,status,target_count)
                    VALUES (:repair,:source,'123e4567-e89b-12d3-a456-426614174138',:run_date,
                      'PROFILE_REPAIR_V1',100,1,'RUNNING',1)
                    """)
                .param("repair", REPAIR_COLLECTION_ID)
                .param("source", COLLECTION_ID)
                .param("run_date", runDate)
                .update();

        var cloned = store.open(
                REPAIR_COLLECTION_ID, PNU, BuildingRegisterEndpoint.BASIC_OVERVIEW, runDate.plusDays(1), 100);

        assertThat(cloned.id()).isNotEqualTo(source.id());
        assertThat(store.completedPage(cloned.id(), 1)).hasValueSatisfying(page -> {
            assertThat(page.totalCount()).isZero();
            assertThat(page.records()).isEmpty();
        });
        assertThat(jdbcClient
                        .sql("""
                    SELECT response_body FROM building_register_raw_page
                    WHERE endpoint_snapshot_id=:snapshot
                    """)
                        .param("snapshot", cloned.id())
                        .query(String.class)
                        .single())
                .isEqualTo("{}");
    }

    private String status(long id) {
        return jdbcClient
                .sql("SELECT status FROM building_register_endpoint_snapshot WHERE id=:id")
                .param("id", id)
                .query(String.class)
                .single();
    }
}
