package com.home.application.ingest.buildingprofile;

import com.home.domain.complex.buildingprofile.BuildingProfileAggregation;
import com.home.domain.complex.buildingprofile.BuildingProfileAreaContribution;
import com.home.domain.complex.buildingprofile.BuildingProfileAssignmentStatus;
import com.home.domain.complex.buildingprofile.BuildingProfileComparisonStatus;
import com.home.domain.complex.buildingprofile.BuildingProfileField;
import com.home.domain.complex.buildingprofile.BuildingProfileQualityMetrics;
import com.home.domain.complex.buildingprofile.BuildingProfileQualityPolicy;
import com.home.domain.complex.buildingprofile.BuildingProfileRatioCalculator;
import com.home.domain.complex.buildingprofile.BuildingProfileScope;
import com.home.domain.complex.buildingprofile.BuildingProfileTypedValue;
import com.home.domain.complex.buildingprofile.BuildingProfileValueState;
import com.home.domain.complex.buildingprofile.BuildingProfileZeroPolicy;
import com.home.domain.complex.buildingregister.BuildingRegisterEndpoint;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class BuildingProfileAnalysisService {
    private static final String WEIGHTED_NATIONAL = "WEIGHTED_NATIONAL";
    private final BuildingProfileAnalysisRepository repository;
    private final BuildingProfileQualityPolicy qualityPolicy = new BuildingProfileQualityPolicy();
    private final BuildingProfileRatioCalculator ratioCalculator = new BuildingProfileRatioCalculator();

    public BuildingProfileAnalysisService(BuildingProfileAnalysisRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public BuildingProfileAnalysisSummary analyze(BuildingProfileAnalysisCommand command) {
        boolean alreadyCompleted = repository.startOrLoad(command);
        List<BuildingProfileAnalysisRecord> records = repository.records(command.parseRunId());
        List<BuildingProfileAnalysisComplex> complexes = repository.complexes(command.collectionId());
        Map<String, List<BuildingProfileAnalysisComplex>> complexesByPnu =
                complexes.stream().collect(Collectors.groupingBy(BuildingProfileAnalysisComplex::pnu));

        List<BuildingProfileAssignmentEvidence> assignments = new ArrayList<>();
        List<BuildingProfileComplexMatchEvidence> matches = new ArrayList<>();
        List<BuildingProfileComparisonEvidence> comparisons = new ArrayList<>();
        Map<String, List<BuildingProfileAnalysisRecord>> byPnu = records.stream()
                .collect(Collectors.groupingBy(
                        BuildingProfileAnalysisRecord::pnu, LinkedHashMap::new, Collectors.toList()));
        for (var entry : byPnu.entrySet()) {
            analyzePnu(
                    entry.getKey(),
                    entry.getValue(),
                    complexesByPnu.getOrDefault(entry.getKey(), List.of()),
                    assignments,
                    matches,
                    comparisons);
        }
        repository.recordAssignments(command.analysisRunId(), assignments);
        repository.recordComplexMatches(command.analysisRunId(), command.collectionId(), matches);
        repository.recordComparisons(command.analysisRunId(), comparisons);

        Map<String, Double> weights = repository.sampleWeights(command.collectionId());
        List<BuildingProfileFieldQualityEvidence> quality = quality(
                records,
                matches,
                comparisons,
                weights,
                repository.sampleStrata(command.collectionId()),
                repository.operationalCompletion(command.collectionId()),
                repository.operationalCompletionByStratum(command.collectionId()));
        repository.recordFieldQuality(command.analysisRunId(), quality);
        BuildingProfileReportStats reportStats = repository.reportStats(command.collectionId(), command.parseRunId());
        List<Path> reportFiles =
                writeReports(command.outputDirectory(), command, quality, comparisons, matches, reportStats);
        repository.complete(command.analysisRunId(), manifest(reportFiles));
        return new BuildingProfileAnalysisSummary(
                assignments.size(), matches.size(), comparisons.size(), quality.size(), reportFiles, alreadyCompleted);
    }

    private void analyzePnu(
            String pnu,
            List<BuildingProfileAnalysisRecord> source,
            List<BuildingProfileAnalysisComplex> complexes,
            List<BuildingProfileAssignmentEvidence> assignments,
            List<BuildingProfileComplexMatchEvidence> matches,
            List<BuildingProfileComparisonEvidence> comparisons) {
        Map<String, List<BuildingProfileAnalysisRecord>> identity = source.stream()
                .collect(Collectors.groupingBy(
                        record -> record.endpoint() + ":" + record.managementKey(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        boolean sourceConflict = identity.values().stream()
                .anyMatch(duplicates -> duplicates.stream()
                                .map(BuildingProfileAnalysisRecord::values)
                                .distinct()
                                .count()
                        > 1);
        List<BuildingProfileAnalysisRecord> distinct =
                identity.values().stream().map(List::getFirst).toList();
        List<BuildingProfileAnalysisRecord> roots = distinct.stream()
                .filter(record -> record.endpoint() == BuildingRegisterEndpoint.RECAP_TITLE)
                .filter(record -> record.registerKindCode() == 1)
                .filter(record -> record.managementKey() != null)
                .toList();
        List<BuildingProfileAnalysisRecord> newRoots = roots.stream()
                .filter(record -> "1".equals(record.newOldRegisterCode()))
                .toList();
        BuildingProfileAnalysisRecord root = null;
        BuildingProfileAssignmentStatus overallStatus;
        String reason = null;
        if (sourceConflict) {
            overallStatus = BuildingProfileAssignmentStatus.SOURCE_CONFLICT;
            reason = "conflicting duplicate management key";
        } else if (newRoots.size() > 1 || (roots.size() > 1 && newRoots.isEmpty())) {
            overallStatus = BuildingProfileAssignmentStatus.AMBIGUOUS_GENERATION;
            reason = "single new-generation root cannot be selected";
        } else if (newRoots.size() == 1) {
            root = newRoots.getFirst();
            overallStatus = BuildingProfileAssignmentStatus.RESOLVED;
        } else if (roots.size() == 1) {
            root = roots.getFirst();
            overallStatus = BuildingProfileAssignmentStatus.RESOLVED;
        } else {
            List<BuildingProfileAnalysisRecord> standalone = distinct.stream()
                    .filter(record -> record.endpoint() == BuildingRegisterEndpoint.TITLE)
                    .filter(record -> record.registerKindCode() == 2 || record.registerKindCode() == 3)
                    .filter(record -> record.managementKey() != null)
                    .toList();
            if (standalone.size() == 1) {
                root = standalone.getFirst();
                overallStatus = BuildingProfileAssignmentStatus.RESOLVED;
            } else {
                overallStatus = BuildingProfileAssignmentStatus.SOURCE_MISSING;
                reason = "root source is missing";
            }
        }

        String rootKey = root == null ? null : root.managementKey();
        BuildingProfileAnalysisRecord selectedRoot = root;
        List<BuildingProfileAnalysisRecord> titles = root == null
                ? List.of()
                : distinct.stream()
                        .filter(record -> record.endpoint() == BuildingRegisterEndpoint.TITLE)
                        .filter(record -> record.registerKindCode() == 2 || record.registerKindCode() == 3)
                        .filter(record -> record == selectedRoot || rootKey.equals(record.parentManagementKey()))
                        .toList();
        Set<String> expectedKeys = root == null
                ? Set.of()
                : distinct.stream()
                        .filter(record -> record.endpoint() == BuildingRegisterEndpoint.BASIC_OVERVIEW)
                        .filter(record -> record.registerKindCode() == 2 || record.registerKindCode() == 3)
                        .filter(record -> rootKey.equals(record.parentManagementKey()))
                        .map(BuildingProfileAnalysisRecord::managementKey)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> actualKeys = titles.stream()
                .map(BuildingProfileAnalysisRecord::managementKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        boolean hierarchyComplete = expectedKeys.isEmpty() || actualKeys.containsAll(expectedKeys);
        if (overallStatus == BuildingProfileAssignmentStatus.RESOLVED && !hierarchyComplete) {
            overallStatus = BuildingProfileAssignmentStatus.INCOMPLETE_HIERARCHY;
            reason = "expected title management key is missing";
        }

        for (BuildingProfileAnalysisRecord record : source) {
            boolean assigned = root != null
                    && (record.recordId() == root.recordId() || Objects.equals(rootKey, record.parentManagementKey()));
            BuildingProfileAssignmentStatus status = overallStatus == BuildingProfileAssignmentStatus.RESOLVED
                    ? (assigned ? BuildingProfileAssignmentStatus.RESOLVED : BuildingProfileAssignmentStatus.ORPHAN)
                    : overallStatus;
            assignments.add(new BuildingProfileAssignmentEvidence(
                    record.recordId(),
                    assigned ? rootKey : null,
                    assigned ? rootKey : null,
                    status,
                    status == BuildingProfileAssignmentStatus.ORPHAN ? "record is outside selected root" : reason));
        }

        for (BuildingProfileAnalysisComplex complex : complexes) {
            BuildingProfileAssignmentStatus status = overallStatus;
            boolean projectable = status == BuildingProfileAssignmentStatus.RESOLVED && complex.pnuComplexCount() == 1;
            if (status == BuildingProfileAssignmentStatus.RESOLVED && complex.pnuComplexCount() > 1) {
                status = BuildingProfileAssignmentStatus.SHARED_SCOPE;
            }
            matches.add(new BuildingProfileComplexMatchEvidence(
                    complex.complexId(),
                    pnu,
                    rootKey,
                    status,
                    projectable,
                    status == BuildingProfileAssignmentStatus.SHARED_SCOPE ? "shared PNU scope" : reason));
        }

        if (root != null && overallStatus == BuildingProfileAssignmentStatus.RESOLVED) {
            compareFields(pnu, root, titles, hierarchyComplete, comparisons);
        }
    }

    private void compareFields(
            String pnu,
            BuildingProfileAnalysisRecord recap,
            List<BuildingProfileAnalysisRecord> titles,
            boolean hierarchyComplete,
            List<BuildingProfileComparisonEvidence> output) {
        String scopeHash = sha256(pnu + "\u0000" + recap.managementKey());
        for (BuildingProfileField field : BuildingProfileField.values()) {
            if (field.scope() == BuildingProfileScope.HIERARCHY) continue;
            BuildingProfileTypedValue recapValue = recap.value(field);
            List<BuildingProfileTypedValue> titleValues =
                    titles.stream().map(title -> title.value(field)).toList();
            if (recapValue == null && titleValues.stream().allMatch(Objects::isNull)) continue;
            output.add(compare(scopeHash, field, recapValue, titleValues, titles, hierarchyComplete, recap));
        }
    }

    private BuildingProfileComparisonEvidence compare(
            String scopeHash,
            BuildingProfileField field,
            BuildingProfileTypedValue recap,
            List<BuildingProfileTypedValue> titleValues,
            List<BuildingProfileAnalysisRecord> titles,
            boolean hierarchyComplete,
            BuildingProfileAnalysisRecord recapRecord) {
        List<BuildingProfileTypedValue> validTitles =
                titleValues.stream().filter(value -> valid(field, value)).toList();
        int expected = titles.size();
        if (field.aggregation() == BuildingProfileAggregation.SUM) {
            if (!hierarchyComplete || expected == 0 || validTitles.size() != expected) {
                return evidence(
                        scopeHash,
                        field,
                        BuildingProfileComparisonStatus.INCOMPLETE,
                        recap,
                        null,
                        null,
                        validTitles.size(),
                        expected);
            }
            BigDecimal sum = validTitles.stream().map(this::number).reduce(BigDecimal.ZERO, BigDecimal::add);
            return numericEvidence(scopeHash, field, recap, sum, validTitles.size(), expected);
        }
        if (field.aggregation() == BuildingProfileAggregation.RECALCULATED) {
            BigDecimal platArea = number(recapRecord.value(BuildingProfileField.PLAT_AREA));
            List<BuildingProfileAreaContribution> contributions = titles.stream()
                    .map(title -> new BuildingProfileAreaContribution(
                            title.managementKey(),
                            number(title.value(BuildingProfileField.ARCH_AREA)),
                            number(title.value(BuildingProfileField.VL_RAT_ESTM_TOT_AREA))))
                    .toList();
            BigDecimal calculated = field == BuildingProfileField.BC_RAT
                    ? ratioCalculator.buildingCoverageRatio(platArea, contributions, hierarchyComplete)
                    : ratioCalculator.floorAreaRatio(platArea, contributions, hierarchyComplete);
            if (calculated == null) {
                return evidence(
                        scopeHash,
                        field,
                        BuildingProfileComparisonStatus.INCOMPLETE,
                        recap,
                        null,
                        null,
                        validTitles.size(),
                        expected);
            }
            return numericEvidence(scopeHash, field, recap, calculated, expected, expected);
        }
        if (validTitles.isEmpty()) {
            return evidence(
                    scopeHash, field, BuildingProfileComparisonStatus.NOT_COMPARABLE, recap, null, null, 0, expected);
        }
        if (field.aggregation() == BuildingProfileAggregation.MAX) {
            BigDecimal max = validTitles.stream()
                    .map(this::number)
                    .filter(Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(null);
            return numericEvidence(scopeHash, field, recap, max, validTitles.size(), expected);
        }
        Set<String> titleSet =
                validTitles.stream().map(this::display).collect(Collectors.toCollection(LinkedHashSet::new));
        String aggregate = String.join("|", titleSet);
        if (field.aggregation() == BuildingProfileAggregation.SET) {
            BuildingProfileComparisonStatus status = valid(field, recap) && titleSet.contains(display(recap))
                    ? BuildingProfileComparisonStatus.MATCH
                    : BuildingProfileComparisonStatus.CONFLICT;
            return evidence(scopeHash, field, status, recap, aggregate, null, validTitles.size(), expected);
        }
        if (titleSet.size() != 1) {
            return evidence(
                    scopeHash,
                    field,
                    BuildingProfileComparisonStatus.CONFLICT,
                    recap,
                    aggregate,
                    null,
                    validTitles.size(),
                    expected);
        }
        if (number(recap) != null && number(validTitles.getFirst()) != null) {
            return numericEvidence(
                    scopeHash, field, recap, number(validTitles.getFirst()), validTitles.size(), expected);
        }
        BuildingProfileComparisonStatus status =
                valid(field, recap) && display(recap).equals(aggregate)
                        ? BuildingProfileComparisonStatus.MATCH
                        : BuildingProfileComparisonStatus.CONFLICT;
        return evidence(scopeHash, field, status, recap, aggregate, null, validTitles.size(), expected);
    }

    private BuildingProfileComparisonEvidence numericEvidence(
            String scopeHash,
            BuildingProfileField field,
            BuildingProfileTypedValue recap,
            BigDecimal title,
            int contributors,
            int expected) {
        BigDecimal direct = number(recap);
        if (direct == null || title == null) {
            return evidence(
                    scopeHash,
                    field,
                    BuildingProfileComparisonStatus.NOT_COMPARABLE,
                    recap,
                    title == null ? null : title.toPlainString(),
                    null,
                    contributors,
                    expected);
        }
        BigDecimal difference = direct.subtract(title).abs();
        BigDecimal tolerance = tolerance(field);
        BuildingProfileComparisonStatus status = difference.signum() == 0
                ? BuildingProfileComparisonStatus.MATCH
                : difference.compareTo(tolerance) <= 0
                        ? BuildingProfileComparisonStatus.WITHIN_TOLERANCE
                        : BuildingProfileComparisonStatus.CONFLICT;
        return evidence(scopeHash, field, status, recap, title.toPlainString(), difference, contributors, expected);
    }

    private BigDecimal tolerance(BuildingProfileField field) {
        if (field == BuildingProfileField.BC_RAT || field == BuildingProfileField.VL_RAT) return new BigDecimal("0.01");
        if (field.valueType() == com.home.domain.complex.buildingprofile.BuildingProfileValueType.DECIMAL) {
            return new BigDecimal("0.001");
        }
        return BigDecimal.ZERO;
    }

    private BuildingProfileComparisonEvidence evidence(
            String scopeHash,
            BuildingProfileField field,
            BuildingProfileComparisonStatus status,
            BuildingProfileTypedValue recap,
            String title,
            BigDecimal difference,
            int contributors,
            int expected) {
        return new BuildingProfileComparisonEvidence(
                scopeHash,
                field,
                field.aggregation(),
                status,
                display(recap),
                title,
                difference,
                contributors,
                expected);
    }

    private List<BuildingProfileFieldQualityEvidence> quality(
            List<BuildingProfileAnalysisRecord> records,
            List<BuildingProfileComplexMatchEvidence> matches,
            List<BuildingProfileComparisonEvidence> comparisons,
            Map<String, Double> storedWeights,
            Map<String, String> sampleStrata,
            double operationalCompletion,
            Map<String, Double> operationalCompletionByStratum) {
        Map<String, Double> weights = storedWeights.isEmpty()
                ? records.stream()
                        .map(BuildingProfileAnalysisRecord::pnu)
                        .distinct()
                        .collect(Collectors.toMap(value -> value, ignored -> 1.0d))
                : storedWeights;
        List<BuildingProfileFieldQualityEvidence> output = new ArrayList<>();
        Map<String, String> scopePnus = records.stream()
                .filter(record -> record.managementKey() != null)
                .collect(Collectors.toMap(
                        record -> sha256(record.pnu() + "\u0000" + record.managementKey()),
                        BuildingProfileAnalysisRecord::pnu,
                        (left, right) -> left));
        for (BuildingProfileField field : BuildingProfileField.values()) {
            if (field.scope() == BuildingProfileScope.HIERARCHY) continue;
            output.add(qualityRow(
                    field,
                    WEIGHTED_NATIONAL,
                    records,
                    matches,
                    comparisons,
                    weights,
                    operationalCompletion,
                    scopePnus));
            sampleStrata.values().stream().distinct().sorted().forEach(stratum -> {
                Map<String, Double> stratumWeights = weights.entrySet().stream()
                        .filter(entry -> stratum.equals(sampleStrata.get(entry.getKey())))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                output.add(qualityRow(
                        field,
                        stratum,
                        records,
                        matches,
                        comparisons,
                        stratumWeights,
                        operationalCompletionByStratum.getOrDefault(stratum, 0.0d),
                        scopePnus));
            });
        }
        return output;
    }

    private BuildingProfileFieldQualityEvidence qualityRow(
            BuildingProfileField field,
            String stratum,
            List<BuildingProfileAnalysisRecord> records,
            List<BuildingProfileComplexMatchEvidence> matches,
            List<BuildingProfileComparisonEvidence> comparisons,
            Map<String, Double> weights,
            double operationalCompletion,
            Map<String, String> scopePnus) {
        Set<String> pnuSet = weights.keySet();
        Map<String, List<BuildingProfileAnalysisRecord>> byPnu = records.stream()
                .filter(record -> pnuSet.contains(record.pnu()))
                .collect(Collectors.groupingBy(BuildingProfileAnalysisRecord::pnu));
        List<BuildingProfileAnalysisRecord> eligible = records.stream()
                .filter(record -> pnuSet.contains(record.pnu()))
                .filter(record -> record.endpoint() != BuildingRegisterEndpoint.BASIC_OVERVIEW)
                .filter(record -> field.scope() == BuildingProfileScope.SITE
                        ? record.endpoint() == BuildingRegisterEndpoint.RECAP_TITLE
                        : record.endpoint() == BuildingRegisterEndpoint.TITLE)
                .toList();
        long validRecords = eligible.stream()
                .filter(record -> valid(field, record.value(field)))
                .count();
        long invalidRecords = eligible.stream()
                .filter(record -> invalid(field, record.value(field)))
                .count();
        double sourceCoverage = rate(validRecords, eligible.size());
        double totalWeight =
                weights.values().stream().mapToDouble(Double::doubleValue).sum();
        double pnuNumerator = weights.entrySet().stream()
                .filter(entry -> byPnu.getOrDefault(entry.getKey(), List.of()).stream()
                        .anyMatch(record -> valid(field, record.value(field))))
                .mapToDouble(Map.Entry::getValue)
                .sum();
        double pnuCoverage = rate(pnuNumerator, totalWeight);
        List<BuildingProfileComplexMatchEvidence> eligibleMatches =
                matches.stream().filter(match -> pnuSet.contains(match.pnu())).toList();
        long readyComplexes = eligibleMatches.stream()
                .filter(BuildingProfileComplexMatchEvidence::projectable)
                .filter(match -> byPnu.getOrDefault(match.pnu(), List.of()).stream()
                        .anyMatch(record -> valid(field, record.value(field))))
                .count();
        double readiness = rate(readyComplexes, eligibleMatches.size());
        List<BuildingProfileComparisonEvidence> comparable = comparisons.stream()
                .filter(value -> pnuSet.contains(scopePnus.get(value.scopeHash())))
                .filter(value -> value.field() == field)
                .filter(value -> value.status() == BuildingProfileComparisonStatus.MATCH
                        || value.status() == BuildingProfileComparisonStatus.WITHIN_TOLERANCE
                        || value.status() == BuildingProfileComparisonStatus.CONFLICT)
                .toList();
        long conflicts = comparable.stream()
                .filter(value -> value.status() == BuildingProfileComparisonStatus.CONFLICT)
                .count();
        double conflictRate = rate(conflicts, comparable.size());
        double invalidRate = rate(invalidRecords, eligible.size());
        double buildingCoverage = field.scope() == BuildingProfileScope.BUILDING ? sourceCoverage : 0;
        var metrics = new BuildingProfileQualityMetrics(
                field.scope(),
                sourceCoverage,
                buildingCoverage,
                pnuCoverage,
                readiness,
                invalidRate,
                conflictRate,
                true);
        double[] wilson = wilson(pnuCoverage, weights.size());
        return new BuildingProfileFieldQualityEvidence(
                field,
                stratum,
                sourceCoverage,
                buildingCoverage,
                pnuCoverage,
                readiness,
                operationalCompletion,
                invalidRate,
                conflictRate,
                wilson[0],
                wilson[1],
                qualityPolicy.classify(metrics),
                true,
                pnuNumerator,
                totalWeight);
    }

    private boolean valid(BuildingProfileField field, BuildingProfileTypedValue value) {
        if (value == null || !value.state().hasTypedValue()) return false;
        return value.state() != BuildingProfileValueState.ZERO || field.zeroPolicy() == BuildingProfileZeroPolicy.VALID;
    }

    private boolean invalid(BuildingProfileField field, BuildingProfileTypedValue value) {
        return value != null
                && (value.state() == BuildingProfileValueState.INVALID
                        || (value.state() == BuildingProfileValueState.ZERO
                                && field.zeroPolicy() == BuildingProfileZeroPolicy.INVALID));
    }

    private BigDecimal number(BuildingProfileTypedValue value) {
        if (value == null) return null;
        if (value.decimalValue() != null) return value.decimalValue();
        return value.integerValue() == null ? null : BigDecimal.valueOf(value.integerValue());
    }

    private String display(BuildingProfileTypedValue value) {
        if (value == null || !value.state().hasTypedValue()) return null;
        if (value.textValue() != null) return value.textValue();
        if (value.decimalValue() != null) return value.decimalValue().toPlainString();
        if (value.integerValue() != null) return value.integerValue().toString();
        if (value.dateValue() != null) return value.dateValue().toString();
        return value.booleanValue() == null ? null : value.booleanValue().toString();
    }

    private double rate(double numerator, double denominator) {
        return denominator <= 0 ? 0 : numerator / denominator;
    }

    private double[] wilson(double proportion, int sampleSize) {
        if (sampleSize <= 0) return new double[] {0, 0};
        double z = 1.959963984540054d;
        double z2 = z * z;
        double denominator = 1 + z2 / sampleSize;
        double center = (proportion + z2 / (2 * sampleSize)) / denominator;
        double margin =
                z * Math.sqrt((proportion * (1 - proportion) + z2 / (4 * sampleSize)) / sampleSize) / denominator;
        return new double[] {Math.max(0, center - margin), Math.min(1, center + margin)};
    }

    private List<Path> writeReports(
            Path outputDirectory,
            BuildingProfileAnalysisCommand command,
            List<BuildingProfileFieldQualityEvidence> quality,
            List<BuildingProfileComparisonEvidence> comparisons,
            List<BuildingProfileComplexMatchEvidence> matches,
            BuildingProfileReportStats reportStats) {
        try {
            if (Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(outputDirectory)) {
                throw new IllegalArgumentException("outputDirectory must not be a symbolic link");
            }
            Files.createDirectories(outputDirectory);
            Path csv = safeOutput(outputDirectory, "building-register-profile-quality.csv");
            Path json = safeOutput(outputDirectory, "building-register-profile-quality.json");
            Path markdown = safeOutput(outputDirectory, "building-register-profile-quality.md");
            write(csv, csv(quality));
            write(json, json(command, quality, comparisons, matches, reportStats));
            write(markdown, markdown(command, quality, comparisons, matches, reportStats));
            return List.of(csv, json, markdown);
        } catch (IOException exception) {
            throw new IllegalStateException("profile report write failed", exception);
        }
    }

    private Path safeOutput(Path directory, String fileName) {
        Path output = directory.resolve(fileName).normalize();
        if (!output.startsWith(directory)) throw new IllegalArgumentException("report path escaped outputDirectory");
        return output;
    }

    private void write(Path path, String content) throws IOException {
        Files.writeString(
                path,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private String csv(List<BuildingProfileFieldQualityEvidence> rows) {
        StringBuilder value = new StringBuilder(
                "field_id,scope,stratum,source_record_coverage,building_coverage,pnu_coverage,projectable_complex_readiness,operational_completion,invalid_rate,conflict_rate,wilson_low,wilson_high,quality_tier\n");
        rows.forEach(row -> value.append(row.field().name())
                .append(',')
                .append(row.field().scope().name())
                .append(',')
                .append(row.stratum())
                .append(',')
                .append(row.sourceRecordCoverage())
                .append(',')
                .append(row.buildingCoverage())
                .append(',')
                .append(row.pnuCoverage())
                .append(',')
                .append(row.projectableComplexReadiness())
                .append(',')
                .append(row.operationalCompletion())
                .append(',')
                .append(row.invalidRate())
                .append(',')
                .append(row.conflictRate())
                .append(',')
                .append(row.wilsonLow())
                .append(',')
                .append(row.wilsonHigh())
                .append(',')
                .append(row.qualityTier().name())
                .append('\n'));
        return value.toString();
    }

    private String json(
            BuildingProfileAnalysisCommand command,
            List<BuildingProfileFieldQualityEvidence> rows,
            List<BuildingProfileComparisonEvidence> comparisons,
            List<BuildingProfileComplexMatchEvidence> matches,
            BuildingProfileReportStats reportStats) {
        String fields = rows.stream()
                .map(row -> String.format(
                        java.util.Locale.ROOT,
                        "{\"fieldId\":\"%s\",\"scope\":\"%s\",\"stratum\":\"%s\",\"pnuCoverage\":%.10f,\"projectableComplexReadiness\":%.10f,\"operationalCompletion\":%.10f,\"invalidRate\":%.10f,\"conflictRate\":%.10f,\"wilson95\":[%.10f,%.10f],\"qualityTier\":\"%s\"}",
                        row.field().name(),
                        row.field().scope().name(),
                        row.stratum(),
                        row.pnuCoverage(),
                        row.projectableComplexReadiness(),
                        row.operationalCompletion(),
                        row.invalidRate(),
                        row.conflictRate(),
                        row.wilsonLow(),
                        row.wilsonHigh(),
                        row.qualityTier().name()))
                .collect(Collectors.joining(","));
        long conflicts = comparisons.stream()
                .filter(value -> value.status() == BuildingProfileComparisonStatus.CONFLICT)
                .count();
        long shared = matches.stream()
                .filter(value -> value.status() == BuildingProfileAssignmentStatus.SHARED_SCOPE)
                .count();
        return "{\"rulesVersion\":\"" + command.rulesVersion() + "\",\"summary\":{\"comparisons\":"
                + comparisons.size() + ",\"conflicts\":" + conflicts + ",\"sharedScopes\":" + shared
                + ",\"profileStorageBytes\":" + reportStats.profileStorageBytes()
                + "},\"endpointStatusCounts\":" + jsonMap(reportStats.endpointStatusCounts())
                + ",\"valueStateCounts\":" + jsonMap(reportStats.valueStateCounts())
                + ",\"codeTransitionCounts\":" + jsonMap(reportStats.codeTransitionCounts())
                + ",\"fields\":[" + fields + "]}\n";
    }

    private String markdown(
            BuildingProfileAnalysisCommand command,
            List<BuildingProfileFieldQualityEvidence> rows,
            List<BuildingProfileComparisonEvidence> comparisons,
            List<BuildingProfileComplexMatchEvidence> matches,
            BuildingProfileReportStats reportStats) {
        StringBuilder value = new StringBuilder("# 건축물대장 Profile 품질 보고서\n\n")
                .append("- rulesVersion: `")
                .append(command.rulesVersion())
                .append("`\n")
                .append("- field 수: ")
                .append(rows.size())
                .append("\n")
                .append("- 비교 건수: ")
                .append(comparisons.size())
                .append("\n")
                .append("- shared scope 건수: ")
                .append(matches.stream()
                        .filter(match -> match.status() == BuildingProfileAssignmentStatus.SHARED_SCOPE)
                        .count())
                .append("\n- profile 저장량(bytes): ")
                .append(reportStats.profileStorageBytes())
                .append("\n\n## 수집·상태 evidence\n\n")
                .append("- endpoint/status: ")
                .append(reportStats.endpointStatusCounts())
                .append('\n')
                .append("- value state(field/state): ")
                .append(reportStats.valueStateCounts())
                .append('\n')
                .append("- 법정동코드 전환 결과: ")
                .append(reportStats.codeTransitionCounts())
                .append("\n\n")
                .append("## 필드별 품질\n\n")
                .append(
                        "| 필드 | scope | stratum | PNU coverage | projectable readiness | operational | invalid | conflict | tier |\n")
                .append("|---|---|---|---:|---:|---:|---:|---:|---|\n");
        rows.forEach(row -> value.append('|')
                .append(row.field().name())
                .append('|')
                .append(row.field().scope().name())
                .append('|')
                .append(row.stratum())
                .append('|')
                .append(formatPercent(row.pnuCoverage()))
                .append('|')
                .append(formatPercent(row.projectableComplexReadiness()))
                .append('|')
                .append(formatPercent(row.operationalCompletion()))
                .append('|')
                .append(formatPercent(row.invalidRate()))
                .append('|')
                .append(formatPercent(row.conflictRate()))
                .append('|')
                .append(row.qualityTier().name())
                .append("|\n"));
        value.append("\nPNU·관리번호·raw body·서비스키는 보고서에 포함하지 않았다.\n");
        return value.toString();
    }

    private String jsonMap(Map<String, Long> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private String formatPercent(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f%%", value * 100);
    }

    private String manifest(List<Path> files) {
        return "{\"files\":["
                + files.stream()
                        .map(path -> "{\"name\":\"" + path.getFileName() + "\",\"sha256\":\"" + sha256(path) + "\"}")
                        .collect(Collectors.joining(","))
                + "]}";
    }

    private String sha256(Path path) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("report hash failed", exception);
        }
    }

    private String sha256(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) value.append(String.format("%02x", current));
        return value.toString();
    }
}
