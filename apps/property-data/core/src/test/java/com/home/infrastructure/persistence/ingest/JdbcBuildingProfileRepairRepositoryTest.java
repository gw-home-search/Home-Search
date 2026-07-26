package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.buildingprofile.BuildingProfileRepairCommand;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingProfileRepairRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcBuildingProfileRepairRepositoryTest extends JdbcPostgresTestSupport {
    private static final UUID SOURCE = UUID.fromString("123e4567-e89b-12d3-a456-426614174330");
    private static final UUID REPAIR = UUID.fromString("123e4567-e89b-12d3-a456-426614174331");
    private static final UUID REQUEST = UUID.fromString("123e4567-e89b-12d3-a456-426614174332");

    @Test
    @DisplayName("provider failure와 hierarchy reason PNU만 repair target으로 멱등 freeze한다")
    void freezesProviderFailuresAndHierarchyGapsIdempotently() {
        seedSource();
        JdbcBuildingProfileRepairRepository repository =
                new JdbcBuildingProfileRepairRepository(jdbcClient, transactionTemplate);
        BuildingProfileRepairCommand command = new BuildingProfileRepairCommand(
                SOURCE, REPAIR, REQUEST, LocalDate.of(2026, 7, 27), "PROFILE_REPAIR_V1", 20_000, 4);

        assertThat(repository.freezeOrLoad(command))
                .extracting("pnu")
                .containsExactly("1168010300101400001", "1168010300101400002");
        assertThat(repository.freezeOrLoad(command)).hasSize(2);
        assertThat(jdbcClient
                        .sql("""
                    SELECT count(*) FROM building_register_collection_target WHERE collection_id=:collection
                    """)
                        .param("collection", REPAIR)
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    private void seedSource() {
        jdbcClient.sql("""
                    INSERT INTO region(id,code,name,region_type)
                    VALUES (99330,'1168010398','repair','eup-myeon-dong')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO parcel(id,region_id,pnu,address,latitude,longitude) VALUES
                      (99331,99330,'1168010300101400001','one',37.5,127.0),
                      (99332,99330,'1168010300101400002','two',37.5,127.0)
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO complex(id,parcel_id,complex_pk,name) VALUES
                      (99331,99331,'REPAIR-1','one'),(99332,99332,'REPAIR-2','two')
                    """).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign(
                      collection_id,mode,strategy,to_complex_id,status,completed_at,purpose,target_scope,
                      selection_seed,sample_size)
                    VALUES (:source,'profile','COMPARE_RECAP_TITLE',99332,'COMPLETED',now(),
                      'PROFILE_DISCOVERY','NATIONWIDE_STAGING','source',2)
                    """).param("source", SOURCE).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_endpoint_snapshot(
                      collection_id,pnu,endpoint,run_date,page_size,attempt_no,status,completed_at)
                    VALUES (:source,'1168010300101400001','TITLE','2026-07-20',100,1,'PROVIDER_FAILED',now())
                    """).param("source", SOURCE).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_hierarchy_reason(collection_id,pnu,reason)
                    VALUES (:source,'1168010300101400002','MISSING_PARENT')
                    """).param("source", SOURCE).update();
    }
}
