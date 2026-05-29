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

	private final RtmsApartmentTradeClient client;
	private final Supplier<OpenApiTradeIngestService> ingestServiceSupplier;
	private final RtmsIngestRunRepository ingestRunRepository;
	private final Clock clock;

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
		this.client = Objects.requireNonNull(client);
		this.ingestServiceSupplier = Objects.requireNonNull(ingestServiceSupplier);
		this.ingestRunRepository = Objects.requireNonNull(ingestRunRepository);
		this.clock = Objects.requireNonNull(clock);
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
		RtmsApartmentTradeRequest currentRequest = firstRequest;
		IngestResult total = IngestResult.empty();
		int pageCount = 0;
		while (true) {
			RtmsApartmentTradePage page = client.fetchPage(currentRequest);
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
}
