package com.home.batch.launch;

import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.ingestcore.rtms.RtmsDealMonth;
import com.home.ingestcore.rtms.RtmsLawdCode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.boot.ApplicationArguments;

public record BatchJobArguments(String jobName, JobParameters jobParameters) {

    private static final String DAILY_JOB = "rtmsDailyRefreshJob";
    private static final String BACKFILL_JOB = "rtmsBackfillJob";
    private static final String BUILDING_METADATA_JOB = "complexBuildingMetadataJob";
    private static final String ODC_METADATA_GAP_FILL_JOB = "complexOdcMetadataGapFillJob";
    private static final String BUILDING_REGISTER_COLLECT_JOB = "complexBuildingRegisterCollectJob";
    private static final String BUILDING_RATIO_PROJECT_JOB = "complexBuildingRatioProjectJob";
    private static final String BUILDING_PROFILE_REPLAY_JOB = "complexBuildingRegisterProfileReplayJob";
    private static final String BUILDING_PROFILE_COLLECT_JOB = "complexBuildingRegisterProfileCollectJob";
    private static final String BUILDING_PROFILE_ANALYZE_JOB = "complexBuildingRegisterProfileAnalyzeJob";
    private static final String LEGAL_DONG_CODE_IMPORT_JOB = "legalDongCodeMappingImportJob";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static BatchJobArguments from(String jobName, ApplicationArguments arguments, Clock clock) {
        return from(jobName, argumentMap(arguments), clock);
    }

    static BatchJobArguments from(String jobName, Map<String, String> arguments, Clock clock) {
        String normalizedJobName = requireText(jobName, "SPRING_BATCH_JOB_NAME is required");
        Map<String, String> params = arguments == null ? Map.of() : Map.copyOf(arguments);
        return switch (normalizedJobName) {
            case DAILY_JOB -> daily(normalizedJobName, params, clock);
            case BACKFILL_JOB -> backfill(normalizedJobName, params);
            case BUILDING_METADATA_JOB -> buildingMetadata(normalizedJobName, params, clock);
            case ODC_METADATA_GAP_FILL_JOB -> odcMetadataGapFill(normalizedJobName, params, clock);
            case BUILDING_REGISTER_COLLECT_JOB -> buildingRegisterCollect(normalizedJobName, params, clock);
            case BUILDING_RATIO_PROJECT_JOB -> buildingRatioProject(normalizedJobName, params, clock);
            case BUILDING_PROFILE_REPLAY_JOB -> buildingProfileReplay(normalizedJobName, params);
            case BUILDING_PROFILE_COLLECT_JOB -> buildingProfileCollect(normalizedJobName, params, clock);
            case BUILDING_PROFILE_ANALYZE_JOB -> buildingProfileAnalyze(normalizedJobName, params);
            case LEGAL_DONG_CODE_IMPORT_JOB -> legalDongCodeImport(normalizedJobName, params);
            default -> throw invalid("Unsupported SPRING_BATCH_JOB_NAME: " + normalizedJobName);
        };
    }

    private static BatchJobArguments buildingProfileReplay(String jobName, Map<String, String> arguments) {
        return new BatchJobArguments(
                jobName,
                parameters(Map.of(
                        "sourceCollectionId", canonicalUuid(arguments.get("sourceCollectionId"), "sourceCollectionId"),
                        "parseRunId", canonicalUuid(arguments.get("parseRunId"), "parseRunId"),
                        "parserVersion", requireText(arguments.get("parserVersion"), "parserVersion is required"),
                        "maxPages", Integer.toString(positiveInt(arguments.get("maxPages"), "maxPages")))));
    }

    private static BatchJobArguments buildingProfileCollect(
            String jobName, Map<String, String> arguments, Clock clock) {
        requireLiteral(arguments, "purpose", "profile-discovery");
        requireLiteral(arguments, "targetScope", "validation-sample");
        requireLiteral(arguments, "strategy", "compare-recap-title");
        int sampleSize = positiveInt(arguments.get("sampleSize"), "sampleSize");
        if (sampleSize != 1500) throw invalid("sampleSize must be exactly 1500");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("collectionId", canonicalUuid(arguments.get("collectionId"), "collectionId"));
        values.put("requestId", canonicalRequestId(arguments.get("requestId")));
        values.put("runDate", runDate(arguments.get("runDate"), clock));
        values.put("purpose", "profile-discovery");
        values.put("targetScope", "validation-sample");
        values.put("strategy", "compare-recap-title");
        values.put("sampleSize", Integer.toString(sampleSize));
        values.put("selectionSeed", requireText(arguments.get("selectionSeed"), "selectionSeed is required"));
        values.put("maxRequests", Integer.toString(positiveInt(arguments.get("maxRequests"), "maxRequests")));
        values.put("parallelism", Integer.toString(profileParallelism(arguments.get("parallelism"))));
        return new BatchJobArguments(jobName, parameters(values));
    }

    private static BatchJobArguments buildingProfileAnalyze(String jobName, Map<String, String> arguments) {
        return new BatchJobArguments(
                jobName,
                parameters(Map.of(
                        "collectionId", canonicalUuid(arguments.get("collectionId"), "collectionId"),
                        "parseRunId", canonicalUuid(arguments.get("parseRunId"), "parseRunId"),
                        "analysisRunId", canonicalUuid(arguments.get("analysisRunId"), "analysisRunId"),
                        "rulesVersion", requireText(arguments.get("rulesVersion"), "rulesVersion is required"),
                        "outputDirectory",
                                requireText(arguments.get("outputDirectory"), "outputDirectory is required"))));
    }

    private static BatchJobArguments legalDongCodeImport(String jobName, Map<String, String> arguments) {
        return new BatchJobArguments(
                jobName,
                parameters(Map.of(
                        "importId", canonicalUuid(arguments.get("importId"), "importId"),
                        "effectiveDate", requiredDate(arguments.get("effectiveDate"), "effectiveDate"),
                        "sourceFile", requireText(arguments.get("sourceFile"), "sourceFile is required"))));
    }

    private static void requireLiteral(Map<String, String> arguments, String name, String expected) {
        if (!expected.equals(requireText(arguments.get(name), name + " is required"))) {
            throw invalid(name + " must be " + expected);
        }
    }

    private static int profileParallelism(String value) {
        if (text(value) == null) return 2;
        int parallelism = positiveInt(value, "parallelism");
        if (parallelism > 4) throw invalid("parallelism must be at most 4");
        return parallelism;
    }

    private static BatchJobArguments buildingRegisterCollect(
            String jobName, Map<String, String> arguments, Clock clock) {
        String mode = requireText(arguments.get("mode"), "mode is required");
        if (!List.of("missing", "retry").contains(mode)) throw invalid("mode must be missing or retry");
        String strategy = requireText(arguments.get("strategy"), "strategy is required");
        if (!List.of("adaptive", "full-hierarchy").contains(strategy)) {
            throw invalid("strategy must be adaptive or full-hierarchy");
        }
        Long fromId = optionalPositiveLong(arguments.get("fromComplexId"), "fromComplexId");
        Long toId = optionalPositiveLong(arguments.get("toComplexId"), "toComplexId");
        if (toId == null) throw invalid("toComplexId is required");
        if (fromId != null && fromId > toId) throw invalid("fromComplexId must be <= toComplexId");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("collectionId", canonicalUuid(arguments.get("collectionId"), "collectionId"));
        values.put("requestId", canonicalRequestId(arguments.get("requestId")));
        values.put("runDate", runDate(arguments.get("runDate"), clock));
        values.put("mode", mode);
        values.put("strategy", strategy);
        values.put("maxRequests", Integer.toString(positiveInt(arguments.get("maxRequests"), "maxRequests")));
        values.put("parallelism", Integer.toString(boundedParallelism(arguments.get("parallelism"))));
        values.put("toComplexId", toId.toString());
        if (fromId != null) values.put("fromComplexId", fromId.toString());
        return new BatchJobArguments(jobName, parameters(values));
    }

    private static int boundedParallelism(String value) {
        if (text(value) == null) return 1;
        int parallelism = positiveInt(value, "parallelism");
        if (parallelism > 4) throw invalid("parallelism must be at most 4");
        return parallelism;
    }

    private static BatchJobArguments buildingRatioProject(String jobName, Map<String, String> arguments, Clock clock) {
        Long fromId = optionalPositiveLong(arguments.get("fromComplexId"), "fromComplexId");
        Long toId = optionalPositiveLong(arguments.get("toComplexId"), "toComplexId");
        if (fromId != null && toId != null && fromId > toId) {
            throw invalid("fromComplexId must be <= toComplexId");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("collectionId", canonicalUuid(arguments.get("collectionId"), "collectionId"));
        values.put("requestId", canonicalRequestId(arguments.get("requestId")));
        values.put("runDate", runDate(arguments.get("runDate"), clock));
        values.put("maxTargets", Integer.toString(positiveInt(arguments.get("maxTargets"), "maxTargets")));
        if (fromId != null) values.put("fromComplexId", fromId.toString());
        if (toId != null) values.put("toComplexId", toId.toString());
        return new BatchJobArguments(jobName, parameters(values));
    }

    private static BatchJobArguments odcMetadataGapFill(String jobName, Map<String, String> arguments, Clock clock) {
        String runDate = text(arguments.get("runDate"));
        if (runDate == null)
            runDate = LocalDate.now((clock == null ? Clock.system(KST) : clock).withZone(KST))
                    .toString();
        try {
            LocalDate.parse(runDate);
        } catch (RuntimeException exception) {
            throw invalid("runDate must use yyyy-MM-dd");
        }
        int maxTargets = positiveInt(arguments.get("maxTargets"), "maxTargets");
        Long fromId = optionalPositiveLong(arguments.get("fromComplexId"), "fromComplexId");
        Long toId = optionalPositiveLong(arguments.get("toComplexId"), "toComplexId");
        if (toId == null) throw invalid("toComplexId is required");
        if (fromId != null && fromId > toId) throw invalid("fromComplexId must be <= toComplexId");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("runDate", runDate);
        values.put("maxTargets", Integer.toString(maxTargets));
        values.put("toComplexId", toId.toString());
        values.put("requestId", canonicalRequestId(arguments.get("requestId")));
        if (fromId != null) values.put("fromComplexId", fromId.toString());
        return new BatchJobArguments(jobName, parameters(values));
    }

    private static BatchJobArguments buildingMetadata(String jobName, Map<String, String> arguments, Clock clock) {
        String mode = requireText(arguments.get("mode"), "mode is required");
        if (!List.of("missing", "retry").contains(mode)) throw invalid("mode must be missing or retry");
        String runDate = text(arguments.get("runDate"));
        if (runDate == null)
            runDate = LocalDate.now((clock == null ? Clock.system(KST) : clock).withZone(KST))
                    .toString();
        try {
            LocalDate.parse(runDate);
        } catch (RuntimeException exception) {
            throw invalid("runDate must use yyyy-MM-dd");
        }
        int maxRequests = positiveInt(arguments.get("maxRequests"), "maxRequests");
        Long fromId = optionalPositiveLong(arguments.get("fromComplexId"), "fromComplexId");
        Long toId = optionalPositiveLong(arguments.get("toComplexId"), "toComplexId");
        if (fromId != null && toId != null && fromId > toId) throw invalid("fromComplexId must be <= toComplexId");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("mode", mode);
        values.put("runDate", runDate);
        values.put("maxRequests", Integer.toString(maxRequests));
        values.put("requestId", canonicalRequestId(arguments.get("requestId")));
        if (fromId != null) values.put("fromComplexId", fromId.toString());
        if (toId != null) values.put("toComplexId", toId.toString());
        return new BatchJobArguments(jobName, parameters(values));
    }

    private static BatchJobArguments daily(String jobName, Map<String, String> arguments, Clock clock) {
        Clock safeClock = clock == null ? Clock.system(KST) : clock;
        String runDate = text(arguments.get("runDate"));
        if (runDate == null) {
            runDate = LocalDate.now(safeClock.withZone(KST)).toString();
        }
        try {
            LocalDate.parse(runDate);
        } catch (RuntimeException exception) {
            throw invalid("runDate must use yyyy-MM-dd");
        }
        Map<String, String> identifyingParameters = new LinkedHashMap<>();
        identifyingParameters.put("runDate", runDate);
        String requestId = canonicalRequestId(arguments.get("requestId"));
        identifyingParameters.put("requestId", requestId);
        return new BatchJobArguments(jobName, parameters(identifyingParameters));
    }

    private static BatchJobArguments backfill(String jobName, Map<String, String> arguments) {
        String fromYmd = RtmsDealMonth.of(requireText(arguments.get("fromYmd"), "fromYmd is required"))
                .value();
        String toYmd = RtmsDealMonth.of(requireText(arguments.get("toYmd"), "toYmd is required"))
                .value();
        if (fromYmd.compareTo(toYmd) > 0) {
            throw invalid("fromYmd must be less than or equal to toYmd");
        }
        String lawdCds = requireText(arguments.get("lawdCds"), "lawdCds is required");
        for (String lawdCd : lawdCds.split(",")) {
            RtmsLawdCode.of(lawdCd);
        }
        String requestId = canonicalRequestId(arguments.get("requestId"));
        return new BatchJobArguments(
                jobName,
                parameters(Map.of(
                        "fromYmd", fromYmd,
                        "toYmd", toYmd,
                        "lawdCds", lawdCds,
                        "requestId", requestId)));
    }

    private static JobParameters parameters(Map<String, String> values) {
        Set<JobParameter<?>> parameters = new LinkedHashSet<>();
        values.forEach((name, value) -> parameters.add(new JobParameter<>(name, value, String.class, true)));
        return new JobParameters(parameters);
    }

    private static Map<String, String> argumentMap(ApplicationArguments arguments) {
        Map<String, String> result = new LinkedHashMap<>();
        if (arguments == null) {
            return result;
        }
        for (String value : arguments.getNonOptionArgs()) {
            int separator = value.indexOf('=');
            if (separator > 0) {
                result.put(value.substring(0, separator), value.substring(separator + 1));
            }
        }
        for (String name : arguments.getOptionNames()) {
            List<String> values = arguments.getOptionValues(name);
            if (values != null && !values.isEmpty()) {
                result.put(name, values.get(values.size() - 1));
            }
        }
        return result;
    }

    private static String requireText(String value, String message) {
        String text = text(value);
        if (text == null) {
            throw invalid(message);
        }
        return text;
    }

    private static String canonicalRequestId(String value) {
        String requestId = requireText(value, "requestId is required");
        try {
            return ExecutionCorrelationId.from(requestId).toString();
        } catch (IllegalArgumentException exception) {
            throw invalid("requestId must be a canonical UUID");
        }
    }

    private static String canonicalUuid(String value, String name) {
        String candidate = requireText(value, name + " is required");
        try {
            String canonical = java.util.UUID.fromString(candidate).toString();
            if (!canonical.equals(candidate)) throw new IllegalArgumentException();
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw invalid(name + " must be a canonical UUID");
        }
    }

    private static String runDate(String value, Clock clock) {
        String result = text(value);
        if (result == null) {
            result = LocalDate.now((clock == null ? Clock.system(KST) : clock).withZone(KST))
                    .toString();
        }
        try {
            LocalDate.parse(result);
            return result;
        } catch (RuntimeException exception) {
            throw invalid("runDate must use yyyy-MM-dd");
        }
    }

    private static String requiredDate(String value, String name) {
        String result = requireText(value, name + " is required");
        try {
            return LocalDate.parse(result).toString();
        } catch (RuntimeException exception) {
            throw invalid(name + " must use yyyy-MM-dd");
        }
    }

    private static int positiveInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(requireText(value, name + " is required"));
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(name + " must be a positive integer");
        }
    }

    private static Long optionalPositiveLong(String value, String name) {
        String text = text(value);
        if (text == null) return null;
        try {
            long parsed = Long.parseLong(text);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid(name + " must be a positive integer");
        }
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static BatchExitCodeException invalid(String message) {
        return new BatchExitCodeException(
                Objects.requireNonNull(message), BatchExitCodeExceptionMapper.INVALID_ARGUMENT_EXIT_CODE);
    }
}
