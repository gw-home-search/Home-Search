package com.home.infrastructure.scheduling.rtms;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.home.application.ingest.trade.IngestResult;
import com.home.application.ingest.trade.OpenApiTradeIngestBatch;
import com.home.application.ingest.trade.OpenApiTradeIngestService;
import com.home.application.ingest.run.RtmsIngestRunRecord;
import com.home.application.ingest.run.RtmsIngestRunRepository;
import com.home.infrastructure.external.rtms.RtmsApartmentTradeClient;
import com.home.infrastructure.external.rtms.RtmsApartmentTradeRequest;
import com.home.infrastructure.external.rtms.RtmsApartmentTradePage;

class RtmsMonthlyRefreshRunner {

	private static final int MAX_FAILURE_REASON_LENGTH = 500;

	private final RtmsApartmentTradeClient client;
	private final Supplier<OpenApiTradeIngestService> ingestServiceSupplier;
	private final Supplier<RtmsIngestRunRepository> ingestRunRepositorySupplier;
	private final Clock clock;
	private final RtmsMonthlyRefreshRetryPolicy retryPolicy;

	RtmsMonthlyRefreshRunner(
		RtmsApartmentTradeClient client,
		Supplier<OpenApiTradeIngestService> ingestServiceSupplier,
		Supplier<RtmsIngestRunRepository> ingestRunRepositorySupplier,
		Clock clock,
		RtmsMonthlyRefreshRetryPolicy retryPolicy
	) {
		this.client = Objects.requireNonNull(client);
		this.ingestServiceSupplier = Objects.requireNonNull(ingestServiceSupplier);
		this.ingestRunRepositorySupplier = Objects.requireNonNull(ingestRunRepositorySupplier);
		this.clock = Objects.requireNonNull(clock);
		this.retryPolicy = Objects.requireNonNull(retryPolicy);
	}

	RtmsMonthlyRefreshRunSummary refresh(String lawdCd, String dealYmd) {
		OpenApiTradeIngestService ingestService = requiredIngestService();
		RtmsApartmentTradeRequest currentRequest = new RtmsApartmentTradeRequest(lawdCd, dealYmd, 1);
		return refreshMonth(ingestService, currentRequest);
	}

	RtmsMonthlyRefreshReport refresh(RtmsMonthlyRefreshPlan plan) {
		Objects.requireNonNull(plan, "plan is required");
		OpenApiTradeIngestService ingestService = requiredIngestService();
		List<RtmsMonthlyRefreshRunSummary> summaries = new ArrayList<>();
		for (RtmsApartmentTradeRequest request : plan.monthlyRequests()) {
			summaries.add(refreshMonth(ingestService, request));
		}
		return new RtmsMonthlyRefreshReport(summaries);
	}

	private RtmsMonthlyRefreshRunSummary refreshMonth(
		OpenApiTradeIngestService ingestService,
		RtmsApartmentTradeRequest firstRequest
	) {
		Instant startedAt = clock.instant();
		RtmsIngestRunRepository ingestRunRepository = Objects.requireNonNull(
			ingestRunRepositorySupplier.get(),
			"RtmsIngestRunRepository is required"
		);
		MonthlyRefreshOutcome outcome = executeMonth(ingestService, firstRequest, startedAt);
		RtmsIngestRunRecord saved = ingestRunRepository.save(outcome.toRecord());
		return outcome.toSummary(saved.id());
	}

	private OpenApiTradeIngestService requiredIngestService() {
		return Objects.requireNonNull(ingestServiceSupplier.get(), "OpenApiTradeIngestService is required");
	}

	private MonthlyRefreshOutcome executeMonth(
		OpenApiTradeIngestService ingestService,
		RtmsApartmentTradeRequest firstRequest,
		Instant startedAt
	) {
		RtmsApartmentTradeRequest currentRequest = firstRequest;
		IngestResult total = IngestResult.empty();
		int pageCount = 0;
		try {
			while (true) {
				RtmsApartmentTradePage page = fetchPageWithRetry(currentRequest);
				OpenApiTradeIngestBatch batch = page.batch();
				total = total.plus(ingestService.ingest(batch));
				pageCount++;
				if (!page.hasNextPage()) {
					return MonthlyRefreshOutcome.completed(
						firstRequest,
						pageCount,
						total,
						startedAt,
						clock.instant()
					);
				}
				currentRequest = page.nextRequest();
			}
		}
		catch (RuntimeException exception) {
			Instant completedAt = clock.instant();
			String failureReason = failureReason(exception);
			if (pageCount > 0) {
				return MonthlyRefreshOutcome.partiallyFailed(
					firstRequest,
					pageCount,
					total,
					failureReason,
					startedAt,
					completedAt
				);
			}
			return MonthlyRefreshOutcome.failed(
				firstRequest,
				pageCount,
				total,
				failureReason,
				startedAt,
				completedAt
			);
		}
	}

	private RtmsApartmentTradePage fetchPageWithRetry(RtmsApartmentTradeRequest request) {
		RuntimeException lastException = null;
		for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
			try {
				return client.fetchPage(request);
			}
			catch (RuntimeException exception) {
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
		}
		catch (InterruptedException exception) {
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
		return value
			.replaceAll("(?i)(serviceKey=)[^&\\s]+", "$1[REDACTED]")
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
		Instant completedAt
	) {

		static MonthlyRefreshOutcome completed(
			RtmsApartmentTradeRequest request,
			int pageCount,
			IngestResult result,
			Instant startedAt,
			Instant completedAt
		) {
			return new MonthlyRefreshOutcome(
				request.lawdCd(),
				request.dealYmd(),
				pageCount,
				result,
				RtmsMonthlyRefreshRunStatus.COMPLETED,
				null,
				startedAt,
				completedAt
			);
		}

		static MonthlyRefreshOutcome partiallyFailed(
			RtmsApartmentTradeRequest request,
			int pageCount,
			IngestResult result,
			String failureReason,
			Instant startedAt,
			Instant completedAt
		) {
			return failedOutcome(
				request,
				pageCount,
				result,
				RtmsMonthlyRefreshRunStatus.PARTIAL,
				failureReason,
				startedAt,
				completedAt
			);
		}

		static MonthlyRefreshOutcome failed(
			RtmsApartmentTradeRequest request,
			int pageCount,
			IngestResult result,
			String failureReason,
			Instant startedAt,
			Instant completedAt
		) {
			return failedOutcome(
				request,
				pageCount,
				result,
				RtmsMonthlyRefreshRunStatus.FAILED,
				failureReason,
				startedAt,
				completedAt
			);
		}

		private static MonthlyRefreshOutcome failedOutcome(
			RtmsApartmentTradeRequest request,
			int pageCount,
			IngestResult result,
			RtmsMonthlyRefreshRunStatus status,
			String failureReason,
			Instant startedAt,
			Instant completedAt
		) {
			return new MonthlyRefreshOutcome(
				request.lawdCd(),
				request.dealYmd(),
				pageCount,
				result,
				status,
				failureReason,
				startedAt,
				completedAt
			);
		}

		RtmsIngestRunRecord toRecord() {
			return RtmsIngestRunRecord.of(
				lawdCd,
				dealYmd,
				pageCount,
				result,
				status.storedValue(),
				status.failureReason(failureReason),
				startedAt,
				completedAt
			);
		}

		RtmsMonthlyRefreshRunSummary toSummary(Long runId) {
			return RtmsMonthlyRefreshRunSummary.of(lawdCd, dealYmd, pageCount, result, status, failureReason, runId);
		}
	}
}
