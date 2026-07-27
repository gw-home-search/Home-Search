package com.home.domain.complex.buildingprofile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingProfilePublicationDomainTest {

    @Test
    @DisplayName("저장 enum은 안정적인 constant와 한국어 metadata를 제공한다")
    void exposesStableStoredMeanings() {
        assertThat(BuildingProfilePublicationStatus.values())
                .extracting(Enum::name)
                .containsExactly("PREPARING", "VALIDATED", "PUBLISHED", "SUPERSEDED", "FAILED");
        assertMetadata(BuildingProfilePublicationStatus.values());
        assertMetadata(BuildingProfilePublicScope.values());
        assertMetadata(BuildingProfilePublicQuality.values());
        assertMetadata(BuildingProfileSourceMethod.values());
        assertMetadata(BuildingProfileConflictStatus.values());
        assertMetadata(BuildingProfileSeismicDesignStatus.values());
    }

    @Test
    @DisplayName("publication은 검증 완료 상태에서만 발행할 수 있다")
    void ownsPublicationTransitions() {
        assertThat(BuildingProfilePublicationStatus.PREPARING.canValidate()).isTrue();
        assertThat(BuildingProfilePublicationStatus.VALIDATED.canPublish()).isTrue();
        assertThat(BuildingProfilePublicationStatus.PUBLISHED.canSupersede()).isTrue();
        assertThat(BuildingProfilePublicationStatus.FAILED.isTerminalFailure()).isTrue();
        assertThat(Arrays.stream(BuildingProfilePublicationStatus.values())
                        .filter(BuildingProfilePublicationStatus::canPublish))
                .containsExactly(BuildingProfilePublicationStatus.VALIDATED);
    }

    private void assertMetadata(DescribedStoredValue[] values) {
        assertThat(values).allSatisfy(value -> {
            assertThat(value.titleKo()).isNotBlank();
            assertThat(value.descriptionKo()).isNotBlank();
        });
    }
}
