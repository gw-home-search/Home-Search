package com.home.infrastructure.external.rtms;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.home.application.ingest.trade.IngestResult;

record RtmsDailyRefreshResult(
	String lawdCd,
	List<String> dealYmds,
	RtmsMonthlyRefreshRunStatus status,
	IngestResult ingestResult,
	int pageCount,
	List<Long> runIds,
	String failureReason
) {

	RtmsDailyRefreshResult {
		lawdCd = Objects.requireNonNull(lawdCd, "lawdCd is required");
		dealYmds = dealYmds == null ? List.of() : List.copyOf(dealYmds);
		status = Objects.requireNonNull(status, "status is required");
		ingestResult = ingestResult == null ? IngestResult.empty() : ingestResult;
		runIds = runIds == null ? List.of() : List.copyOf(runIds);
	}

	static RtmsDailyRefreshResult from(String lawdCd, RtmsMonthlyRefreshReport report) {
		Objects.requireNonNull(report, "report is required");
		List<RtmsMonthlyRefreshRunSummary> runs = report.runs();
		return new RtmsDailyRefreshResult(
			lawdCd,
			runs.stream().map(RtmsMonthlyRefreshRunSummary::dealYmd).toList(),
			aggregateStatus(runs),
			report.totalResult(),
			report.totalPageCount(),
			runs.stream().map(RtmsMonthlyRefreshRunSummary::runId).filter(Objects::nonNull).toList(),
			failureReason(runs)
		);
	}

	static RtmsDailyRefreshResult from(RtmsMonthlyRefreshPlan plan, RtmsMonthlyRefreshReport report) {
		RtmsDailyRefreshResult result = from(plan.lawdCd(), report);
		if (!result.dealYmds().isEmpty()) {
			return result;
		}
		return new RtmsDailyRefreshResult(
			result.lawdCd(),
			plan.dealYmds(),
			result.status(),
			result.ingestResult(),
			result.pageCount(),
			result.runIds(),
			result.failureReason()
		);
	}

	static RtmsDailyRefreshResult failed(RtmsMonthlyRefreshPlan plan, RuntimeException exception) {
		String reason = exception.getClass().getSimpleName();
		if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
			reason = reason + ": " + exception.getMessage();
		}
		return new RtmsDailyRefreshResult(
			plan.lawdCd(),
			plan.dealYmds(),
			RtmsMonthlyRefreshRunStatus.FAILED,
			IngestResult.empty(),
			0,
			List.of(),
			reason
		);
	}

	private static RtmsMonthlyRefreshRunStatus aggregateStatus(List<RtmsMonthlyRefreshRunSummary> runs) {
		if (runs.isEmpty()) {
			return RtmsMonthlyRefreshRunStatus.COMPLETED;
		}
		boolean allFailed = runs.stream()
			.allMatch(run -> run.status() == RtmsMonthlyRefreshRunStatus.FAILED);
		if (allFailed) {
			return RtmsMonthlyRefreshRunStatus.FAILED;
		}
		boolean anyIncomplete = runs.stream()
			.anyMatch(run -> run.status() != RtmsMonthlyRefreshRunStatus.COMPLETED);
		return anyIncomplete ? RtmsMonthlyRefreshRunStatus.PARTIAL : RtmsMonthlyRefreshRunStatus.COMPLETED;
	}

	private static String failureReason(List<RtmsMonthlyRefreshRunSummary> runs) {
		String reason = runs.stream()
			.map(RtmsMonthlyRefreshRunSummary::failureReason)
			.filter(Objects::nonNull)
			.filter(value -> !value.isBlank())
			.collect(Collectors.joining("; "));
		return reason.isBlank() ? null : reason;
	}
}
