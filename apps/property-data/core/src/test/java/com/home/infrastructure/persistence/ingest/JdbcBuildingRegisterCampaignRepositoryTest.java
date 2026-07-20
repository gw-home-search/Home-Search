package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.ingest.buildingregister.BuildingRegisterCampaignCommand;
import com.home.domain.complex.buildingregister.BuildingRatioScope;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionMode;
import com.home.domain.complex.buildingregister.BuildingRegisterCollectionStrategy;
import com.home.domain.complex.buildingregister.BuildingRegisterComplexMatch;
import com.home.domain.complex.buildingregister.BuildingRegisterMatchStatus;
import com.home.infrastructure.persistence.ingest.matching.JdbcBuildingRegisterCampaignRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcBuildingRegisterCampaignRepositoryTest extends JdbcMigrationTestSupport {
    private static final UUID COLLECTION_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174170");
    private static final UUID REQUEST_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174171");
    private JdbcBuildingRegisterCampaignRepository repository;

    @BeforeEach
    void setUp() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcClient
                .sql("INSERT INTO parcel(id,pnu,address) VALUES (1001,'1168010300101400001','Sample')")
                .update();
        jdbcClient
                .sql("INSERT INTO complex(id,parcel_id,complex_pk,name) VALUES (501,1001,'RTMS:501','Sample')")
                .update();
        repository = new JdbcBuildingRegisterCampaignRepository(jdbcClient, transactionTemplate);
    }

    @Test
    void freezesTargetScopeAndRejectsChangedCampaignParameters() {
        var first = repository.freezeOrLoad(command(1000));
        var resumed = repository.freezeOrLoad(command(1000));

        assertThat(first).hasSize(1).isEqualTo(resumed);
        assertThatThrownBy(() -> repository.freezeOrLoad(command(999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frozen");
    }

    @Test
    void persistsTerminalMatchEvidenceAndCompletesCampaign() {
        repository.freezeOrLoad(command(1000));
        long matchId = repository.recordMatch(
                COLLECTION_ID,
                "1168010300101400001",
                1,
                new BuildingRegisterComplexMatch(
                        501,
                        "ROOT-1",
                        BuildingRatioScope.SHARED_RECAP,
                        BuildingRegisterMatchStatus.RESOLVED,
                        null,
                        false,
                        "shared"));

        assertThat(matchId).isPositive();
        assertThat(repository.completeIfAllTargetsMatched(COLLECTION_ID)).isTrue();
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM building_register_match_evidence WHERE match_id=:match")
                        .param("match", matchId)
                        .query(Integer.class)
                        .single())
                .isOne();
    }

    private BuildingRegisterCampaignCommand command(long toComplexId) {
        return new BuildingRegisterCampaignCommand(
                COLLECTION_ID,
                REQUEST_ID,
                LocalDate.of(2026, 7, 20),
                BuildingRegisterCollectionMode.MISSING,
                BuildingRegisterCollectionStrategy.ADAPTIVE,
                10,
                null,
                toComplexId);
    }
}
