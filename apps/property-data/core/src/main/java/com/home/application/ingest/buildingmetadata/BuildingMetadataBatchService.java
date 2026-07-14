package com.home.application.ingest.buildingmetadata;

import com.home.domain.complex.buildingmetadata.BuildingMetadataSourceKind;
import com.home.domain.complex.metadata.ComplexMetadataFailureKind;
import com.home.domain.complex.metadata.ComplexMetadataStatus;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BuildingMetadataBatchService {
    private final BuildingMetadataEvidenceRepository repository;
    private final BuildingMetadataSourceClient client;
    private final BuildingMetadataSourceParser parser;

    public BuildingMetadataBatchService(
            BuildingMetadataEvidenceRepository repository,
            BuildingMetadataSourceClient client,
            BuildingMetadataSourceParser parser) {
        this.repository = Objects.requireNonNull(repository);
        this.client = Objects.requireNonNull(client);
        this.parser = Objects.requireNonNull(parser);
    }

    public BuildingMetadataBatchSummary collect(
            String mode, int maxRequests, Long fromComplexId, Long toComplexId, UUID requestId) {
        if (!List.of("missing", "retry").contains(mode))
            throw new IllegalArgumentException("mode must be missing or retry");
        if (maxRequests <= 0) throw new IllegalArgumentException("maxRequests must be positive");
        if (!client.isConfigured()) throw new IllegalStateException("BLD_SERVICE_KEY is required");
        int requests = 0, targets = 0, resolved = 0, review = 0, failed = 0;
        int consecutiveTransientFailures = 0;
        for (BuildingMetadataTarget target :
                repository.findTargets(mode, maxRequests, fromComplexId, toComplexId, requestId)) {
            BuildingMetadataAttemptResult result;
            if (target.pnuComplexCount() != 1) {
                targets++;
                result = repository.recordAmbiguousPnu(target, requestId);
            } else {
                if (requests >= maxRequests) break;
                targets++;
                BuildingMetadataSourceKind primary = target.currentValues().dongCnt() != null
                                && target.currentValues().dongCnt() == 1
                        ? BuildingMetadataSourceKind.BLD_TITLE
                        : BuildingMetadataSourceKind.BLD_RECAP_TITLE;
                FetchResult fetched = fetch(target, primary, requestId);
                requests++;
                consecutiveTransientFailures = nextTransientCount(consecutiveTransientFailures, fetched);
                stopOnProviderOutage(consecutiveTransientFailures);
                if (fetched.empty() && requests < maxRequests) {
                    BuildingMetadataSourceKind fallback = primary == BuildingMetadataSourceKind.BLD_TITLE
                            ? BuildingMetadataSourceKind.BLD_RECAP_TITLE
                            : BuildingMetadataSourceKind.BLD_TITLE;
                    fetched = fetch(target, fallback, requestId);
                    requests++;
                    consecutiveTransientFailures = nextTransientCount(consecutiveTransientFailures, fetched);
                    stopOnProviderOutage(consecutiveTransientFailures);
                }
                result = fetched.result() != null
                        ? fetched.result()
                        : repository.recordFailure(
                                target,
                                fetched.source(),
                                ComplexMetadataStatus.UNAVAILABLE,
                                ComplexMetadataFailureKind.SOURCE_MISSING,
                                "source candidate unavailable",
                                requestId,
                                Instant.now().plusSeconds(30L * 86_400));
            }
            if (result.status().isResolvedLike()) resolved++;
            else if (result.status().isFailed()) failed++;
            else review++;
        }
        return new BuildingMetadataBatchSummary(targets, requests, resolved, review, failed);
    }

    private FetchResult fetch(BuildingMetadataTarget target, BuildingMetadataSourceKind source, UUID requestId) {
        try {
            BuildingMetadataSourceResponse response = client.fetch(source, target.pnu());
            if (response.payloadOversized()) {
                return permanentFailure(target, source, requestId, "building source payload exceeds 2 MiB");
            }
            if (response.httpStatus() == null || response.httpStatus() < 200 || response.httpStatus() >= 300) {
                int status = response.httpStatus() == null ? 0 : response.httpStatus();
                if (status == 401 || status == 403 || status == 429) {
                    repository.recordFailure(
                            target,
                            source,
                            ComplexMetadataStatus.FAILED,
                            ComplexMetadataFailureKind.PERMANENT,
                            "building source authentication or quota failure",
                            requestId,
                            null);
                    throw new FatalBuildingMetadataException("building source authentication or quota failure");
                }
                return status >= 400 && status < 500
                        ? permanentFailure(target, source, requestId, "building source HTTP failure")
                        : transientFailure(target, source, requestId, "building source HTTP failure");
            }
            ParsedBuildingMetadataSource parsed = parser.parse(response);
            if (parsed.totalCount() > 100) {
                return new FetchResult(
                        repository.recordFailure(
                                target,
                                source,
                                ComplexMetadataStatus.AMBIGUOUS,
                                ComplexMetadataFailureKind.AMBIGUOUS,
                                "source totalCount exceeds 100",
                                requestId,
                                null),
                        false,
                        source);
            }
            if (parsed.candidates().isEmpty()) {
                return new FetchResult(null, true, source);
            }
            return new FetchResult(repository.apply(target, source, parsed, requestId), false, source);
        } catch (FatalBuildingMetadataException exception) {
            throw exception;
        } catch (BuildingMetadataProviderException exception) {
            BuildingMetadataAttemptResult result = repository.recordFailure(
                    target,
                    source,
                    ComplexMetadataStatus.FAILED,
                    exception.failureKind(),
                    exception.getMessage(),
                    requestId,
                    null);
            if (exception.fatal()) throw new FatalBuildingMetadataException(exception.getMessage());
            return new FetchResult(result, false, source, false);
        } catch (RuntimeException exception) {
            return transientFailure(target, source, requestId, "building source request or parsing failed");
        }
    }

    private FetchResult transientFailure(
            BuildingMetadataTarget target, BuildingMetadataSourceKind source, UUID requestId, String reason) {
        return new FetchResult(
                repository.recordFailure(
                        target,
                        source,
                        ComplexMetadataStatus.FAILED,
                        ComplexMetadataFailureKind.TRANSIENT,
                        reason,
                        requestId,
                        Instant.now().plusSeconds(86_400)),
                false,
                source,
                true);
    }

    private FetchResult permanentFailure(
            BuildingMetadataTarget target, BuildingMetadataSourceKind source, UUID requestId, String reason) {
        return new FetchResult(
                repository.recordFailure(
                        target,
                        source,
                        ComplexMetadataStatus.FAILED,
                        ComplexMetadataFailureKind.PERMANENT,
                        reason,
                        requestId,
                        null),
                false,
                source,
                false);
    }

    private int nextTransientCount(int current, FetchResult fetched) {
        return fetched.transientFailure() ? current + 1 : 0;
    }

    private void stopOnProviderOutage(int consecutiveTransientFailures) {
        if (consecutiveTransientFailures >= 3) {
            throw new IllegalStateException("building metadata provider failed transiently 3 consecutive times");
        }
    }

    private record FetchResult(
            BuildingMetadataAttemptResult result,
            boolean empty,
            BuildingMetadataSourceKind source,
            boolean transientFailure) {
        private FetchResult(BuildingMetadataAttemptResult result, boolean empty, BuildingMetadataSourceKind source) {
            this(result, empty, source, false);
        }
    }

    private static class FatalBuildingMetadataException extends IllegalStateException {
        FatalBuildingMetadataException(String message) {
            super(message);
        }
    }
}
