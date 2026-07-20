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

    private String status(long id) {
        return jdbcClient
                .sql("SELECT status FROM building_register_endpoint_snapshot WHERE id=:id")
                .param("id", id)
                .query(String.class)
                .single();
    }
}
