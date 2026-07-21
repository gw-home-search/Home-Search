package com.home.domain.complex.buildingregister;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.complex.buildingprofile.BuildingProfileField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingRegisterDomainMetadataTest {
    @Test
    @DisplayName("건축물대장 도메인 상태와 메타데이터를 검증한다")
    void persistedBuildingRegisterEnumsExposeKoreanOperationalMetadata() {
        for (BuildingRatioField value : BuildingRatioField.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRatioProjectionOutcome value : BuildingRatioProjectionOutcome.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRatioResolutionMethod value : BuildingRatioResolutionMethod.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRatioResolutionStatus value : BuildingRatioResolutionStatus.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRatioScope value : BuildingRatioScope.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRegisterCollectionMode value : BuildingRegisterCollectionMode.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRegisterCollectionStrategy value : BuildingRegisterCollectionStrategy.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRegisterEndpoint value : BuildingRegisterEndpoint.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRegisterHierarchyStatus value : BuildingRegisterHierarchyStatus.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRegisterMatchPath value : BuildingRegisterMatchPath.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRegisterMatchStatus value : BuildingRegisterMatchStatus.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingRegisterRawPageStatus value : BuildingRegisterRawPageStatus.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
        for (BuildingProfileField value : BuildingProfileField.values())
            assertMetadata(value.titleKo(), value.descriptionKo());
    }

    @Test
    @DisplayName("건축물대장 도메인 상태와 메타데이터를 검증한다")
    void rawPageStateMachineOnlyAllowsFinalizationAndParseRecovery() {
        assertThat(BuildingRegisterRawPageStatus.RECEIVED.isFinalized()).isFalse();
        assertThat(BuildingRegisterRawPageStatus.PARSED.isFinalized()).isTrue();
        assertThat(BuildingRegisterRawPageStatus.RECEIVED.canTransitionTo(BuildingRegisterRawPageStatus.PARSED))
                .isTrue();
        assertThat(BuildingRegisterRawPageStatus.RECEIVED.canTransitionTo(null)).isFalse();
        assertThat(BuildingRegisterRawPageStatus.PARSED.canTransitionTo(BuildingRegisterRawPageStatus.EMPTY))
                .isFalse();
        assertThat(BuildingRegisterRawPageStatus.PARSE_FAILED.canTransitionTo(BuildingRegisterRawPageStatus.PARSED))
                .isTrue();
        assertThat(BuildingRegisterRawPageStatus.PARSE_FAILED.canTransitionTo(BuildingRegisterRawPageStatus.EMPTY))
                .isTrue();
        assertThat(BuildingRegisterRawPageStatus.PARSE_FAILED.canTransitionTo(
                        BuildingRegisterRawPageStatus.PARSE_FAILED))
                .isTrue();
    }

    private void assertMetadata(String titleKo, String descriptionKo) {
        assertThat(titleKo).isNotBlank();
        assertThat(descriptionKo).isNotBlank();
        assertThat(titleKo).containsPattern("[가-힣]");
        assertThat(descriptionKo).containsPattern("[가-힣]");
    }
}
