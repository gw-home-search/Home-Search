package com.home.domain.complex.buildingprofile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildingRegisterProfileSamplerTest {
    @Test
    @DisplayName("동일 seed는 strata가 중복되지 않는 정확한 1500 PNU 표본과 weight를 재현한다")
    void selectsDeterministicFrozenSample() {
        List<BuildingProfileSampleCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 2_000; index++) {
            String pnu = String.format("%010d%09d", 1100000000 + index % 17, index);
            candidates.add(new BuildingProfileSampleCandidate(
                    pnu,
                    pnu.substring(0, 2),
                    index < 20 ? 2 : 1,
                    index % 40,
                    index >= 20 && index < 80 ? (index % 2 == 0 ? "INCHEON" : "GWANGJU_JEONNAM") : null,
                    index >= 80 && index < 130,
                    index % 3 == 0));
        }
        BuildingProfileSampler sampler = new BuildingProfileSampler();

        BuildingProfileSampleSelection first = sampler.select(candidates, 1_500, "profile-v1-seed");
        BuildingProfileSampleSelection second = sampler.select(candidates, 1_500, "profile-v1-seed");

        assertThat(first).isEqualTo(second);
        assertThat(first.entries()).hasSize(1_500);
        assertThat(first.entries()).extracting(BuildingProfileSampleEntry::pnu).doesNotHaveDuplicates();
        assertThat(first.strata())
                .allSatisfy(stratum -> assertThat(stratum.samplingWeight())
                        .isEqualTo((double) stratum.populationCount() / stratum.sampleCount()));
        assertThat(first.entries().stream().filter(entry -> entry.stratum() == BuildingProfileSampleStratum.SHARED_PNU))
                .hasSize(20)
                .allSatisfy(entry -> assertThat(entry.samplingWeight()).isEqualTo(1.0d));
    }
}
