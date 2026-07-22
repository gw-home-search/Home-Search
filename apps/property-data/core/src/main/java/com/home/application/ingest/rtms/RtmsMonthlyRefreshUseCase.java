package com.home.application.ingest.rtms;

import com.home.application.ingest.run.RtmsIngestRunRecord;
import com.home.application.ingest.run.RtmsIngestRunRepository;
import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.trade.OpenApiTradeIngestBatch;
import com.home.application.ingest.trade.OpenApiTradeIngestService;
import com.home.domain.ingest.run.ExecutionCorrelationId;
import com.home.domain.ingest.run.RtmsMonthlyRefreshRunStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class RtmsMonthlyRefreshUseCase {

    private static final int MAX_FAILURE_REASON_LENGTH = 500;

    private final RtmsApartmentTradePageGateway client;
    private final OpenApiTradeIngestService ingestService;
    private final RtmsIngestRunRepository ingestRunRepository;
    private final Clock clock;
    private final RtmsMonthlyRefreshRetryPolicy retryPolicy;

    public RtmsMonthlyRefreshUseCase(
            RtmsApartmentTradePageGateway client,
            OpenApiTradeIngestService ingestService,
            RtmsIngestRunRepository ingestRunRepository,
            RtmsMonthlyRefreshExecution execution) {
        this.client = Objects.requireNonNull(client);
        this.ingestService = Objects.requireNonNull(ingestService);
        this.ingestRunRepository = Objects.requireNonNull(ingestRunRepository);
        RtmsMonthlyRefreshExecution requiredExecution = Objects.requireNonNull(execution);
        this.clock = requiredExecution.clock();
        this.retryPolicy = requiredExecution.retryPolicy();
    }

    public RtmsMonthlyRefreshRunSummary refresh(String lawdCd, String dealYmd) {
        return refresh(lawdCd, dealYmd, null);
    }

    public RtmsMonthlyRefreshRunSummary refresh(
            String lawdCd, String dealYmd, ExecutionCorrelationId executionCorrelationId) {
        RtmsApartmentTradeRequest currentRequest = new RtmsApartmentTradeRequest(lawdCd, dealYmd, 1);
        return refreshMonth(ingestService, currentRequest, executionCorrelationId);
    }

    public RtmsMonthlyRefreshReport refresh(RtmsMonthlyRefreshPlan plan) {
        Objects.requireNonNull(plan, "plan is required");
        List<RtmsMonthlyRefreshRunSummary> summaries = new ArrayList<>();
        for (RtmsApartmentTradeRequest request : plan.monthlyRequests()) {
            summaries.add(refreshMonth(ingestService, request, null));
        }
        return new RtmsMonthlyRefreshReport(summaries);
    }

    private RtmsMonthlyRefreshRunSummary refreshMonth(
            OpenApiTradeIngestService ingestService,
            RtmsApartmentTradeRequest firstRequest,
            ExecutionCorrelationId executionCorrelationId) {
        Instant startedAt = clock.instant();
        MonthlyRefreshOutcome outcome = executeMonth(ingestService, firstRequest, startedAt, executionCorrelationId);
        RtmsIngestRunRecord saved = ingestRunRepository.save(outcome.toRecord(executionCorrelationId));
        return outcome.toSummary(saved.id());
    }

    private MonthlyRefreshOutcome executeMonth(
            OpenApiTradeIngestService ingestService,
            RtmsApartmentTradeRequest firstRequest,
            Instant startedAt,
            ExecutionCorrelationId executionCorrelationId) {
        RtmsApartmentTradeRequest currentRequest = firstRequest;
        IngestResult total = IngestResult.empty();
        int pageCount = 0;
        try {
            while (true) {
                RtmsApartmentTradePage page = fetchPageWithRetry(currentRequest);
                OpenApiTradeIngestBatch batch = page.batch();
                total = total.plus(
                        executionCorrelationId == null
                                ? ingestService.ingest(batch)
                                : ingestService.ingest(batch, executionCorrelationId));
                pageCount++;
                if (!page.hasNextPage()) {
                    return MonthlyRefreshOutcome.completed(firstRequest, pageCount, total, startedAt, clock.instant());
                }
                currentRequest = page.nextRequest();
            }
        } catch (RuntimeException exception) {
            Instant completedAt = clock.instant();
            String failureReason = failureReason(exception);
            if (pageCount > 0) {
                return MonthlyRefreshOutcome.partiallyFailed(
                        firstRequest, pageCount, total, failureReason, startedAt, completedAt);
            }
            return MonthlyRefreshOutcome.failed(firstRequest, pageCount, total, failureReason, startedAt, completedAt);
        }
    }

    private RtmsApartmentTradePage fetchPageWithRetry(RtmsApartmentTradeRequest request) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
            try {
                return client.fetchPage(request);
            } catch (RuntimeException exception) {
                lastException = exception;
                if (attempt >= retryPolicy.maxAttempts()) {
                    throw exception;
                }
                sleepBeforeRetry();
            }
        }
        throw lastException;
    }

    private void sleepBeforeRetry() {
        if (retryPolicy.backoffMillis() == 0) {
            return;
        }
        try {
            Thread.sleep(retryPolicy.backoffMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during RTMS monthly refresh retry backoff", exception);
        }
    }

    private String failureReason(RuntimeException exception) {
        String message = exception.getMessage();
        String reason = exception.getClass().getSimpleName();
        if (message != null && !message.isBlank()) {
            reason = reason + ": " + redactSensitiveQueryValues(message);
        }
        if (reason.length() <= MAX_FAILURE_REASON_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    private String redactSensitiveQueryValues(String value) {
        return value.replaceAll("(?i)(serviceKey=)[^&\\s]+", "$1[REDACTED]")
                .replaceAll("(?i)(service_key=)[^&\\s]+", "$1[REDACTED]");
    }

    private record MonthlyRefreshOutcome(
            String lawdCd,
            String dealYmd,
            int pageCount,
            IngestResult result,
            RtmsMonthlyRefreshRunStatus status,
            String failureReason,
            Instant startedAt,
            Instant completedAt) {

        static MonthlyRefreshOutcome completed(
                RtmsApartmentTradeRequest request,
                int pageCount,
                IngestResult result,
                Instant startedAt,
                Instant completedAt) {
            return new MonthlyRefreshOutcome(
                    request.lawdCd(),
                    request.dealYmd(),
                    pageCount,
                    result,
                    RtmsMonthlyRefreshRunStatus.COMPLETED,
                    null,
                    startedAt,
                    completedAt);
        }

        static MonthlyRefreshOutcome partiallyFailed(
                RtmsApartmentTradeRequest request,
                int pageCount,
                IngestResult result,
                String failureReason,
                Instant startedAt,
                Instant completedAt) {
            return failedOutcome(
                    request,
                    pageCount,
                    result,
                    RtmsMonthlyRefreshRunStatus.PARTIAL,
                    failureReason,
                    startedAt,
                    completedAt);
        }

        static MonthlyRefreshOutcome failed(
                RtmsApartmentTradeRequest request,
                int pageCount,
                IngestResult result,
                String failureReason,
                Instant startedAt,
                Instant completedAt) {
            return failedOutcome(
                    request,
                    pageCount,
                    result,
                    RtmsMonthlyRefreshRunStatus.FAILED,
                    failureReason,
                    startedAt,
                    completedAt);
        }

        private static MonthlyRefreshOutcome failedOutcome(
                RtmsApartmentTradeRequest request,
                int pageCount,
                IngestResult result,
                RtmsMonthlyRefreshRunStatus status,
                String failureReason,
                Instant startedAt,
                Instant completedAt) {
            return new MonthlyRefreshOutcome(
                    request.lawdCd(),
                    request.dealYmd(),
                    pageCount,
                    result,
                    status,
                    failureReason,
                    startedAt,
                    completedAt);
        }

        RtmsIngestRunRecord toRecord(ExecutionCorrelationId executionCorrelationId) {
            return RtmsIngestRunRecord.of(
                    lawdCd,
                    dealYmd,
                    pageCount,
                    result,
                    status.storedValue(),
                    status.failureReason(failureReason),
                    startedAt,
                    completedAt,
                    executionCorrelationId);
        }

        RtmsMonthlyRefreshRunSummary toSummary(Long runId) {
            return RtmsMonthlyRefreshRunSummary.of(lawdCd, dealYmd, pageCount, result, status, failureReason, runId);
        }
    }
}
