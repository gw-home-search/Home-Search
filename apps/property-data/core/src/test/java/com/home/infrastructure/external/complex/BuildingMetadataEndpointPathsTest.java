package com.home.infrastructure.external.complex;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingMetadataEndpointPathsTest {
    @Test
    @DisplayName("canonical title/recap 설정이 역전된 legacy 설정보다 우선한다")
    void canonicalPathsTakePrecedenceOverLegacyPaths() {
        BuildingMetadataEndpointPaths paths = BuildingMetadataEndpointPaths.resolve(
                "/getBrTitleInfo", "/getBrRecapTitleInfo", "/legacyRecap", "/legacyTitle");

        assertThat(paths.title()).isEqualTo("/getBrTitleInfo");
        assertThat(paths.recap()).isEqualTo("/getBrRecapTitleInfo");
    }

    @Test
    @DisplayName("canonical 설정이 없으면 legacy 설정을 과거 의미 그대로 읽는다")
    void legacyPathsKeepHistoricalMeaning() {
        BuildingMetadataEndpointPaths paths =
                BuildingMetadataEndpointPaths.resolve("", "", "/legacyRecap", "/legacyTitle");

        assertThat(paths.title()).isEqualTo("/legacyTitle");
        assertThat(paths.recap()).isEqualTo("/legacyRecap");
        assertThat(paths.usesLegacy()).isTrue();
    }
}
