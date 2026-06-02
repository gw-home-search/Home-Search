package com.home.infrastructure.external.rtms;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.home.application.ingest.IngestResult;
import com.home.application.ingest.OpenApiTradeIngestBatch;
import com.home.application.ingest.OpenApiTradeIngestService;
import com.home.application.ingest.RtmsIngestRunRecord;
import com.home.application.ingest.RtmsIngestRunRepository;

class RtmsMonthlyRefreshRunner {

	private static final int MAX_FAILURE_REASON_LENGTH = 500;

	private final RtmsApartmentTradeClient client;
	private final Supplier<OpenApiTradeIngestService> ingestServiceSupplier;
	private final Supplier<RtmsIngestRunRepository> ingestRunRepositorySupplier;
	private final Clock clock;
	private final RtmsMonthlyRefreshRetryPolicy retryPolicy;

	RtmsMonthlyRefreshRunner(
		RtmsApartmentTradeClient client,
		OpenApiTradeIngestService ingestService
	) {
		this(client, () -> ingestService, RtmsIngestRunRepository.noop(), Clock.systemUTC());
	}

	RtmsMonthlyRefreshRunner(
		RtmsApartmentTradeClient client,
		Supplier<OpenApiTradeIngestService> ingestServiceSupplier
	) {
		this(client, ingestServiceSupplier, RtmsIngestRunRepository.noop(), Clock.systemUTC());
	}

	RtmsMonthlyRefreshRunner(
		RtmsApartmentTradeClient client,
		Supplier<OpenApiTradeIngestService> ingestServiceSupplier,
		RtmsIngestRunRepository ingestRunRepository,
		Clock clock
	) {
		this(
			client,
			ingestServiceSupplier,
			() -> ingestRunRepository,
			clock,
			RtmsMonthlyRefreshRetryPolicy.noBackoffDefault()
		);
	}

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
		OpenApiTradeIngestService ingestService = ingestServiceSupplier.get();
		RtmsApartmentTradeRequest currentRequest = new RtmsApartmentTradeRequest(lawdCd, dealYmd, 1);
		return refreshMonth(ingestService, currentRequest);
	}

	RtmsMonthlyRefreshReport refresh(RtmsMonthlyRefreshPlan plan) {
		Objects.requireNonNull(plan, "plan is required");
		OpenApiTradeIngestService ingestService = ingestServiceSupplier.get();
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
		RtmsIngestRunRepository ingestRunRepository = ingestRunRepositorySupplier.get();
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
					Instant completedAt = clock.instant();
					ingestRunRepository.save(RtmsIngestRunRecord.completed(
						currentRequest.lawdCd(),
						currentRequest.dealYmd(),
						pageCount,
						total,
						startedAt,
						completedAt
					));
					return RtmsMonthlyRefreshRunSummary.completed(
						currentRequest.lawdCd(),
						currentRequest.dealYmd(),
						pageCount,
						total
					);
				}
				currentRequest = page.nextRequest();
			}
		}
		catch (RuntimeException exception) {
			Instant completedAt = clock.instant();
			String failureReason = failureReason(exception);
			if (pageCount > 0) {
				ingestRunRepository.save(RtmsIngestRunRecord.partiallyFailed(
					firstRequest.lawdCd(),
					firstRequest.dealYmd(),
					pageCount,
					total,
					failureReason,
					startedAt,
					completedAt
				));
				return RtmsMonthlyRefreshRunSummary.partiallyFailed(
					firstRequest.lawdCd(),
					firstRequest.dealYmd(),
					pageCount,
					total,
					failureReason
				);
			}
			ingestRunRepository.save(RtmsIngestRunRecord.failed(
				firstRequest.lawdCd(),
				firstRequest.dealYmd(),
				pageCount,
				total,
				failureReason,
				startedAt,
				completedAt
			));
			return RtmsMonthlyRefreshRunSummary.failed(
				firstRequest.lawdCd(),
				firstRequest.dealYmd(),
				pageCount,
				total,
				failureReason
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
}
