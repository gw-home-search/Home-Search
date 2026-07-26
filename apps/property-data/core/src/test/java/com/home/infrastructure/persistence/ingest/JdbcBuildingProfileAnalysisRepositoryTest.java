package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.ingest.buildingprofile.BuildingProfileAnalysisCommand;
import com.home.application.ingest.buildingprofile.BuildingProfileAssignmentEvidence;
import com.home.application.ingest.buildingprofile.BuildingProfileComparisonEvidence;
import com.home.application.ingest.buildingprofile.BuildingProfileComplexMatchEvidence;
import com.home.application.ingest.buildingprofile.BuildingProfileFieldQualityEvidence;
import com.home.domain.complex.buildingprofile.BuildingProfileAggregation;
import com.home.domain.complex.buildingprofile.BuildingProfileAssignmentStatus;
import com.home.domain.complex.buildingprofile.BuildingProfileComparisonStatus;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileQualityTier;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingProfileAnalysisRepository;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcBuildingProfileAnalysisRepositoryTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION = UUID.fromString("123e4567-e89b-12d3-a456-426614174240");
    private static final UUID PARSE = UUID.fromString("123e4567-e89b-12d3-a456-426614174241");
    private static final UUID ANALYSIS = UUID.fromString("123e4567-e89b-12d3-a456-426614174242");
    private static final String PNU = "1168010300101400001";
    private JdbcBuildingProfileAnalysisRepository repository;
    private long recordId;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient
                .sql("INSERT INTO parcel(id,pnu,latitude,longitude) VALUES (9101,:pnu,37.5,127.0)")
                .param("pnu", PNU)
                .update();
        jdbcClient
                .sql(
                        "INSERT INTO complex(id,parcel_id,complex_pk,apt_seq,name,trade_name) VALUES (9102,9101,'P:1','P-1','p','p')")
                .update();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_campaign
                      (collection_id,mode,strategy,to_complex_id,status,purpose,target_scope,selection_seed,sample_size,completed_at)
                    VALUES (:id,'profile','COMPARE_RECAP_TITLE',9102,'COMPLETED','PROFILE_DISCOVERY',
                            'VALIDATION_SAMPLE','seed',1,now())
                    """).param("id", COLLECTION).update();
        jdbcClient
                .sql(
                        "INSERT INTO building_register_profile_sample_stratum VALUES (:id,'REGIONAL_PROPORTIONAL',1,1,'seed',1,now())")
                .param("id", COLLECTION)
                .update();
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_sample_pnu
                      (collection_id,pnu,stratum,seed_rank,sampling_weight,complex_count,collection_status,completed_at)
                    VALUES (:id,:pnu,'REGIONAL_PROPORTIONAL',1,1,1,'COLLECTED',now())
                    """).param("id", COLLECTION).param("pnu", PNU).update();
        jdbcClient.sql("""
                    INSERT INTO building_register_collection_target
                      (collection_id,target_ordinal,complex_id,pnu)
                    VALUES (:id,1,9102,:pnu)
                    """).param("id", COLLECTION).param("pnu", PNU).update();
        long snapshot = jdbcClient
                .sql("""
                    INSERT INTO building_register_endpoint_snapshot
                      (collection_id,pnu,endpoint,run_date,page_size,attempt_no,status,total_count,completed_at)
                    VALUES (:id,:pnu,'RECAP_TITLE',DATE '2026-07-21',100,1,'PARSED',1,now()) RETURNING id
                    """)
                .param("id", COLLECTION)
                .param("pnu", PNU)
                .query(Long.class)
                .single();
        long raw = jdbcClient
                .sql("""
                    INSERT INTO building_register_raw_page
                      (endpoint_snapshot_id,request_id,page_no,attempt_no,status,response_body,body_sha256,byte_count,http_status,finalized_at)
                    VALUES (:snapshot,gen_random_uuid(),1,1,'PARSED','{}',repeat('a',64),2,200,now()) RETURNING id
                    """)
                .param("snapshot", snapshot)
                .query(Long.class)
                .single();
        jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_parse_run
                      (parse_run_id,source_collection_id,parser_version,status,completed_at)
                    VALUES (:parse,:collection,'PROFILE_V1','COMPLETED',now())
                    """)
                .param("parse", PARSE)
                .param("collection", COLLECTION)
                .update();
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_parse_page
                      (parse_run_id,raw_page_id,status,record_count,parsed_at)
                    VALUES (:parse,:raw,'PARSED',1,now())
                    """).param("parse", PARSE).param("raw", raw).update();
        recordId = jdbcClient
                .sql("""
                    INSERT INTO building_register_profile_record
                      (parse_run_id,raw_page_id,item_index,pnu,endpoint,mgm_bldrgst_pk,regstr_kind_cd)
                    VALUES (:parse,:raw,0,:pnu,'RECAP_TITLE','ROOT','1') RETURNING id
                    """)
                .param("parse", PARSE)
                .param("raw", raw)
                .param("pnu", PNU)
                .query(Long.class)
                .single();
        jdbcClient.sql("""
                    INSERT INTO building_register_profile_value
                      (profile_record_id,field_id,field_scope,value_type,zero_policy,value_state,raw_value,decimal_value)
                    VALUES (:record,'PLAT_AREA','SITE','DECIMAL','MISSING_EQUIVALENT','POSITIVE','100',100)
                    """).param("record", recordId).update();
        repository = new JdbcBuildingProfileAnalysisRepository(jdbcClient, transactionTemplate);
    }

    @Test
    @DisplayName("분석 근거 전체를 멱등하게 조회하고 추가한다")
    void readsAndAppendsAllAnalysisEvidenceIdempotently(@TempDir Path output) {
        BuildingProfileAnalysisCommand command =
                new BuildingProfileAnalysisCommand(COLLECTION, PARSE, ANALYSIS, "PROFILE_V1", output);
        assertThat(repository.startOrLoad(command)).isFalse();
        assertThat(repository.recordsPage(PARSE, 0, 5_000)).singleElement().satisfies(record -> {
            assertThat(record.managementKey()).isEqualTo("ROOT");
            assertThat(record.value(BuildingProfileField.PLAT_AREA).decimalValue())
                    .isEqualByComparingTo("100");
        });
        assertThat(repository.complexes(COLLECTION))
                .singleElement()
                .extracting("complexId")
                .isEqualTo(9102L);
        assertThat(repository.sampleWeights(COLLECTION)).containsEntry(PNU, 1.0d);
        assertThat(repository.sampleStrata(COLLECTION)).containsEntry(PNU, "REGIONAL_PROPORTIONAL");
        assertThat(repository.operationalCompletion(COLLECTION)).isEqualTo(1.0d);
        assertThat(repository.operationalCompletionByStratum(COLLECTION)).containsEntry("REGIONAL_PROPORTIONAL", 1.0d);

        repository.recordAssignments(
                ANALYSIS,
                List.of(new BuildingProfileAssignmentEvidence(
                        recordId, "ROOT", "ROOT", BuildingProfileAssignmentStatus.RESOLVED, null)));
        repository.recordComplexMatches(
                ANALYSIS,
                COLLECTION,
                List.of(new BuildingProfileComplexMatchEvidence(
                        9102, PNU, "ROOT", BuildingProfileAssignmentStatus.RESOLVED, true, null)));
        repository.recordComparisons(
                ANALYSIS,
                List.of(new BuildingProfileComparisonEvidence(
                        "a".repeat(64),
                        BuildingProfileField.PLAT_AREA,
                        BuildingProfileAggregation.CONSENSUS,
                        BuildingProfileComparisonStatus.MATCH,
                        "100",
                        "100",
                        BigDecimal.ZERO,
                        1,
                        1)));
        repository.recordFieldQuality(
                ANALYSIS,
                List.of(new BuildingProfileFieldQualityEvidence(
                        BuildingProfileField.PLAT_AREA,
                        "WEIGHTED_NATIONAL",
                        1,
                        0,
                        1,
                        1,
                        1,
                        0,
                        0,
                        .8,
                        1,
                        BuildingProfileQualityTier.PROMOTE_CANDIDATE,
                        true,
                        1,
                        1)));
        assertThat(repository.reportStats(COLLECTION, PARSE).profileStorageBytes())
                .isPositive();
        repository.complete(ANALYSIS, "{\"files\":[]}");
        assertThat(repository.startOrLoad(command)).isTrue();
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM building_register_profile_field_quality WHERE analysis_run_id=:id")
                        .param("id", ANALYSIS)
                        .query(Integer.class)
                        .single())
                .isOne();
    }
}
