package com.home.application.ingest.buildingprofile;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileTypedValue;
import com.home.domain.complex.buildingprofile.BuildingProfileValueState;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildingRegisterProfileAnalysisServiceTest {
    private static final String PNU = "1168010300101400001";

    @Test
    @DisplayName("분석은 vlRat을 vlRatEstmTotArea로 계산하고 식별자를 report에서 제외한다")
    void calculatesRatiosWithoutTotalAreaAndRedactsReports(@TempDir Path output) throws Exception {
        FakeRepository repository = new FakeRepository();
        repository.records = List.of(
                record(
                        1,
                        BuildingRegisterEndpoint.RECAP_TITLE,
                        "ROOT-SECRET",
                        null,
                        1,
                        Map.of(
                                BuildingProfileField.PLAT_AREA, decimal("200"),
                                BuildingProfileField.BC_RAT, decimal("500"),
                                BuildingProfileField.VL_RAT, decimal("50"))),
                record(
                        2,
                        BuildingRegisterEndpoint.TITLE,
                        "TITLE-SECRET",
                        "ROOT-SECRET",
                        3,
                        Map.of(
                                BuildingProfileField.ARCH_AREA, decimal("1000"),
                                BuildingProfileField.TOT_AREA, decimal("9000"),
                                BuildingProfileField.VL_RAT_ESTM_TOT_AREA, decimal("100"))));
        repository.complexes = List.of(new BuildingProfileAnalysisComplex(501, PNU, 1));
        repository.weights = Map.of(PNU, 1.0d);
        BuildingProfileAnalysisCommand command = new BuildingProfileAnalysisCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PROFILE_V1", output);

        BuildingProfileAnalysisSummary summary = new BuildingProfileAnalysisService(repository).analyze(command);

        assertThat(repository.comparisons.stream()
                        .filter(value -> value.field() == BuildingProfileField.VL_RAT)
                        .findFirst())
                .get()
                .extracting(BuildingProfileComparisonEvidence::titleValue)
                .isEqualTo("50");
        assertThat(summary.reportFiles()).hasSize(3);
        assertThat(summary.fieldCount())
                .isEqualTo(2L
                        * java.util.Arrays.stream(BuildingProfileField.values())
                                .filter(field -> field.scope()
                                        != com.home.domain.complex.buildingprofile.BuildingProfileScope.HIERARCHY)
                                .count());
        for (Path report : summary.reportFiles()) {
            assertThat(Files.readString(report)).doesNotContain(PNU, "ROOT-SECRET", "TITLE-SECRET");
        }
    }

    @Test
    @DisplayName("건폐율과 용적률 contributor 완전성은 서로 독립적으로 판정한다")
    void evaluatesRecalculatedRatiosIndependently(@TempDir Path output) {
        FakeRepository repository = new FakeRepository();
        repository.records = List.of(
                record(
                        1,
                        BuildingRegisterEndpoint.RECAP_TITLE,
                        "ROOT",
                        null,
                        1,
                        Map.of(
                                BuildingProfileField.PLAT_AREA,
                                decimal("200"),
                                BuildingProfileField.BC_RAT,
                                decimal("50"))),
                record(
                        2,
                        BuildingRegisterEndpoint.TITLE,
                        "TITLE",
                        "ROOT",
                        3,
                        Map.of(BuildingProfileField.ARCH_AREA, decimal("100"))));
        repository.complexes = List.of(new BuildingProfileAnalysisComplex(501, PNU, 1));
        repository.weights = Map.of(PNU, 1.0d);

        new BuildingProfileAnalysisService(repository)
                .analyze(new BuildingProfileAnalysisCommand(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PROFILE_V1", output));

        assertThat(repository.comparisons.stream()
                        .filter(value -> value.field() == BuildingProfileField.BC_RAT)
                        .findFirst())
                .get()
                .extracting(BuildingProfileComparisonEvidence::status)
                .isEqualTo(com.home.domain.complex.buildingprofile.BuildingProfileComparisonStatus.MATCH);
    }

    @Test
    @DisplayName("site field 충돌은 projectable complex readiness에서 제외한다")
    void excludesConflictingSiteFieldsFromProjectableReadiness(@TempDir Path output) {
        FakeRepository repository = new FakeRepository();
        repository.records = List.of(
                record(
                        1,
                        BuildingRegisterEndpoint.RECAP_TITLE,
                        "ROOT",
                        null,
                        1,
                        Map.of(BuildingProfileField.PLAT_AREA, decimal("200"))),
                record(
                        2,
                        BuildingRegisterEndpoint.TITLE,
                        "TITLE",
                        "ROOT",
                        3,
                        Map.of(BuildingProfileField.PLAT_AREA, decimal("300"))));
        repository.complexes = List.of(new BuildingProfileAnalysisComplex(501, PNU, 1));
        repository.weights = Map.of(PNU, 1.0d);

        new BuildingProfileAnalysisService(repository)
                .analyze(new BuildingProfileAnalysisCommand(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PROFILE_V1", output));

        assertThat(repository.quality.stream()
                        .filter(value -> value.field() == BuildingProfileField.PLAT_AREA)
                        .filter(value -> value.stratum().equals("WEIGHTED_NATIONAL"))
                        .findFirst())
                .get()
                .extracting(BuildingProfileFieldQualityEvidence::projectableComplexReadiness)
                .isEqualTo(0.0d);
    }

    private BuildingProfileAnalysisRecord record(
            long id,
            BuildingRegisterEndpoint endpoint,
            String key,
            String parent,
            int kind,
            Map<BuildingProfileField, BuildingProfileTypedValue> supplied) {
        EnumMap<BuildingProfileField, BuildingProfileTypedValue> values = new EnumMap<>(BuildingProfileField.class);
        values.putAll(supplied);
        return new BuildingProfileAnalysisRecord(id, PNU, endpoint, key, parent, kind, "1", values);
    }

    private BuildingProfileTypedValue decimal(String value) {
        BigDecimal number = new BigDecimal(value);
        return new BuildingProfileTypedValue(
                number.signum() == 0 ? BuildingProfileValueState.ZERO : BuildingProfileValueState.POSITIVE,
                value,
                null,
                number,
                null,
                null,
                null);
    }

    private static final class FakeRepository implements BuildingProfileAnalysisRepository {
        List<BuildingProfileAnalysisRecord> records = List.of();
        List<BuildingProfileAnalysisComplex> complexes = List.of();
        Map<String, Double> weights = Map.of();
        List<BuildingProfileComparisonEvidence> comparisons = new ArrayList<>();
        List<BuildingProfileFieldQualityEvidence> quality = new ArrayList<>();

        @Override
        public boolean startOrLoad(BuildingProfileAnalysisCommand command) {
            return false;
        }

        @Override
        public List<BuildingProfileAnalysisRecord> records(UUID parseRunId) {
            return records;
        }

        @Override
        public List<BuildingProfileAnalysisComplex> complexes(UUID collectionId) {
            return complexes;
        }

        @Override
        public Map<String, Double> sampleWeights(UUID collectionId) {
            return weights;
        }

        @Override
        public Map<String, String> sampleStrata(UUID collectionId) {
            return weights.keySet().stream()
                    .collect(java.util.stream.Collectors.toMap(value -> value, ignored -> "REGIONAL_PROPORTIONAL"));
        }

        @Override
        public double operationalCompletion(UUID collectionId) {
            return 1.0d;
        }

        @Override
        public Map<String, Double> operationalCompletionByStratum(UUID collectionId) {
            return Map.of("REGIONAL_PROPORTIONAL", 1.0d);
        }

        @Override
        public BuildingProfileReportStats reportStats(UUID collectionId, UUID parseRunId) {
            return new BuildingProfileReportStats(
                    Map.of("TITLE:PARSED", 1L), Map.of("PLAT_AREA:POSITIVE", 1L), Map.of(), 1024L);
        }

        @Override
        public void recordAssignments(UUID analysisRunId, List<BuildingProfileAssignmentEvidence> assignments) {}

        @Override
        public void recordComplexMatches(
                UUID analysisRunId, UUID collectionId, List<BuildingProfileComplexMatchEvidence> matches) {}

        @Override
        public void recordComparisons(UUID analysisRunId, List<BuildingProfileComparisonEvidence> values) {
            comparisons = List.copyOf(values);
        }

        @Override
        public void recordFieldQuality(UUID analysisRunId, List<BuildingProfileFieldQualityEvidence> quality) {
            this.quality = List.copyOf(quality);
        }

        @Override
        public void complete(UUID analysisRunId, String reportManifestJson) {}
    }
}
