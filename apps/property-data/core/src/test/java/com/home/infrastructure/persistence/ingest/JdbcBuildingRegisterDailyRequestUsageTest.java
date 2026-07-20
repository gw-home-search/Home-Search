package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingRegisterDailyRequestUsage;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcBuildingRegisterDailyRequestUsageTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174180");
    private JdbcBuildingRegisterDailyRequestUsage usage;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                        (collection_id,mode,strategy,to_complex_id,status)
                    VALUES (:collection,'missing','ADAPTIVE',1000,'COLLECTING')
                    """).param("collection", COLLECTION_ID).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_endpoint_snapshot
                        (id,collection_id,pnu,endpoint,run_date,page_size,attempt_no,status)
                    VALUES
                        (1801,:collection,'1168010300101400001','RECAP_TITLE','2026-07-20',100,1,'PROVIDER_FAILED'),
                        (1802,:collection,'1168010300101400001','RECAP_TITLE','2026-07-21',100,1,'PROVIDER_FAILED')
                    """).param("collection", COLLECTION_ID).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_raw_page
                        (endpoint_snapshot_id,request_id,page_no,attempt_no,status,body_sha256,byte_count)
                    VALUES
                        (1801,'123e4567-e89b-12d3-a456-426614174181',1,1,'PROVIDER_FAILED',repeat('0',64),0),
                        (1801,'123e4567-e89b-12d3-a456-426614174182',2,1,'PROVIDER_FAILED',repeat('0',64),0),
                        (1802,'123e4567-e89b-12d3-a456-426614174183',1,1,'PROVIDER_FAILED',repeat('0',64),0)
                    """).update();
        usage = new JdbcBuildingRegisterDailyRequestUsage(jdbcClient);
    }

    @Test
    @DisplayName("건축물대장 일일 요청 사용량은 실제 raw page를 runDate별로 센다")
    void countsEveryRawPageByEndpointSnapshotRunDate() {
        assertThat(usage.usedRequests(LocalDate.of(2026, 7, 20))).isEqualTo(2);
        assertThat(usage.usedRequests(LocalDate.of(2026, 7, 21))).isOne();
        assertThat(usage.usedRequests(LocalDate.of(2026, 7, 22))).isZero();
    }
}
