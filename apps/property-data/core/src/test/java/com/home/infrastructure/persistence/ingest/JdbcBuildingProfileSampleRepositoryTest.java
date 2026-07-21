package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.ingest.buildingprofile.BuildingProfileCodeLookupEvidence;
import com.home.application.ingest.buildingprofile.BuildingProfileCollectCommand;
import com.home.application.ingest.buildingprofile.LegalDongCodeImportCommand;
import com.home.application.ingest.buildingprofile.LegalDongCodeMapping;
import com.home.domain.complex.buildingprofile.BuildingProfileCodeComparisonStatus;
import com.home.domain.complex.buildingprofile.BuildingProfileHierarchyReason;
import com.home.domain.complex.buildingprofile.BuildingProfileLookupResult;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingProfileSampleRepository;
import com.home.infrastructure.persistence.ingest.matching.JdbcLegalDongCodeImportRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcBuildingProfileSampleRepositoryTest extends JdbcMigrationTestSupport {
    private static final String PNU = "1168010300101400001";
    private static final UUID COLLECTION = UUID.fromString("123e4567-e89b-12d3-a456-426614174230");
    private JdbcBuildingProfileSampleRepository repository;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient
                .sql("INSERT INTO parcel(id,pnu,latitude,longitude) VALUES (9001,:pnu,37.5,127.0)")
                .param("pnu", PNU)
                .update();
        jdbcClient.sql("""
                    INSERT INTO complex(id,parcel_id,complex_pk,apt_seq,name,trade_name,dong_cnt,unit_cnt,use_date,bld_mgm_bld_rgst_pk)
                    VALUES (9002,9001,'PROFILE:1','PROFILE-1','profile','profile',1,10,DATE '2020-01-01','ROOT')
                    """).update();
        repository = new JdbcBuildingProfileSampleRepository(jdbcClient, transactionTemplate);
    }

    @Test
    void freezesSampleAndPersistsResumeHierarchyAndCodeTransitionEvidence() {
        BuildingProfileCollectCommand command = new BuildingProfileCollectCommand(
                COLLECTION, UUID.randomUUID(), LocalDate.of(2026, 7, 21), 1, "fixed-seed", 20, 1);

        assertThat(repository.freezeOrLoad(command)).singleElement().satisfies(target -> {
            assertThat(target.pnu()).isEqualTo(PNU);
            assertThat(target.complexCount()).isOne();
        });
        assertThat(repository.freezeOrLoad(command)).hasSize(1);
        assertThatThrownBy(() -> repository.freezeOrLoad(new BuildingProfileCollectCommand(
                        COLLECTION, UUID.randomUUID(), command.runDate(), 1, "different", 20, 1)))
                .isInstanceOf(IllegalArgumentException.class);

        UUID importId = UUID.randomUUID();
        var legal = new JdbcLegalDongCodeImportRepository(jdbcClient, transactionTemplate);
        LegalDongCodeImportCommand legalCommand = new LegalDongCodeImportCommand(
                importId,
                LocalDate.of(2026, 7, 1),
                "b".repeat(64),
                "official.csv",
                List.of(new LegalDongCodeMapping("1168010300", "1168010400", LocalDate.of(2026, 7, 1))));
        assertThat(legal.importMappings(legalCommand)).isOne();
        assertThat(legal.importMappings(legalCommand)).isOne();
        assertThatThrownBy(() -> legal.importMappings(new LegalDongCodeImportCommand(
                        importId, legalCommand.effectiveDate(), "c".repeat(64), "other.csv", legalCommand.mappings())))
                .isInstanceOf(IllegalArgumentException.class);

        var transition = repository.codeTransition(PNU).orElseThrow();
        assertThat(transition.candidatePnu()).isEqualTo("1168010400101400001");
        assertThatThrownBy(() -> repository.codeTransition("invalid")).isInstanceOf(IllegalArgumentException.class);
        repository.recordCodeLookup(
                COLLECTION,
                new BuildingProfileCodeLookupEvidence(
                        command.requestId(),
                        importId,
                        PNU,
                        transition.candidatePnu(),
                        BuildingProfileLookupResult.SUCCESS,
                        BuildingProfileLookupResult.SUCCESS,
                        BuildingProfileCodeComparisonStatus.CODE_TRANSITION_EQUIVALENT,
                        Set.of("ROOT"),
                        Set.of("ROOT")));

        repository.recordFailure(COLLECTION, PNU, "provider-failed unsafe");
        assertThat(repository.completeIfAllPnusCollected(COLLECTION)).isFalse();
        repository.recordCollected(COLLECTION, PNU, Set.of(BuildingProfileHierarchyReason.MULTIPLE_COMPLEXES));
        assertThat(repository.completedPnus(COLLECTION)).containsExactly(PNU);
        assertThat(repository.completeIfAllPnusCollected(COLLECTION)).isTrue();
        assertThat(jdbcClient
                        .sql("SELECT status FROM building_register_collection_campaign WHERE collection_id=:id")
                        .param("id", COLLECTION)
                        .query(String.class)
                        .single())
                .isEqualTo("COMPLETED");
    }
}
