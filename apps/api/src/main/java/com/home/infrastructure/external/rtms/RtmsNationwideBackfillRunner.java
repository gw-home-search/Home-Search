package com.home.infrastructure.external.rtms;

import java.time.Clock;
import java.util.Objects;

import com.home.application.ingest.RtmsBackfillChunkClaim;
import com.home.application.ingest.RtmsBackfillChunkRepository;
import com.home.application.ingest.RtmsBackfillChunkStatus;
import com.home.application.ingest.RtmsBackfillChunkStatusCounts;
import com.home.application.ingest.RtmsBackfillJobRecord;
import com.home.application.ingest.RtmsBackfillJobRepository;
import com.home.application.ingest.RtmsBackfillJobStatus;

class RtmsNationwideBackfillRunner {

	private final RtmsBackfillJobRepository jobRepository;
	private final RtmsBackfillChunkRepository chunkRepository;
	private final RtmsBackfillChunkExecutor chunkExecutor;
	private final Clock clock;
	private final RtmsNationwideBackfillOptions options;

	RtmsNationwideBackfillRunner(
		RtmsBackfillJobRepository jobRepository,
		RtmsBackfillChunkRepository chunkRepository,
		RtmsBackfillChunkExecutor chunkExecutor,
		Clock clock,
		RtmsNationwideBackfillOptions options
	) {
		this.jobRepository = Objects.requireNonNull(jobRepository);
		this.chunkRepository = Objects.requireNonNull(chunkRepository);
		this.chunkExecutor = Objects.requireNonNull(chunkExecutor);
		this.clock = Objects.requireNonNull(clock);
		this.options = Objects.requireNonNull(options);
	}

	RtmsNationwideBackfillReport run(RtmsNationwideBackfillPlan plan) {
		Objects.requireNonNull(plan, "plan is required");
		RtmsBackfillJobRecord job = jobRepository.createIfAbsent(
			plan.jobKey(),
			"RTMS",
			plan.dealYmdFrom(),
			plan.dealYmdTo(),
			"region.si-gun-gu",
			plan.chunks().size()
		);
		jobRepository.markRunning(job.id());
		chunkRepository.insertChunks(job.id(), plan.chunks(), options.maxAttemptCount());
		int recovered = chunkRepository.recoverStaleRunning(job.id(), "RTMS backfill lease expired");

		int executed = 0;
		while (executed < options.chunkLimit()) {
			RtmsBackfillChunkClaim claim = chunkRepository.claimNextRunnable(
				job.id(),
				options.workerId(),
				options.leaseDuration()
			).orElse(null);
			if (claim == null) {
				break;
			}
			RtmsBackfillChunkExecutionResult result = chunkExecutor.execute(claim.request());
			markChunk(claim, result);
			executed++;
		}

		RtmsBackfillChunkStatusCounts counts = chunkRepository.countStatuses(job.id());
		RtmsBackfillJobStatus status = reconcileJobStatus(counts);
		if (status == RtmsBackfillJobStatus.COMPLETED) {
			jobRepository.markCompleted(job.id());
		}
		else {
			jobRepository.markPartial(job.id(), "RTMS backfill finished with failed, partial, or blocked chunks");
		}
		return new RtmsNationwideBackfillReport(job.id(), status, counts, recovered);
	}

	private void markChunk(RtmsBackfillChunkClaim claim, RtmsBackfillChunkExecutionResult result) {
		if (result.status() == RtmsBackfillChunkStatus.COMPLETED) {
			chunkRepository.markCompleted(claim.id(), result.runId());
			return;
		}
		if (result.status() == RtmsBackfillChunkStatus.PARTIAL) {
			chunkRepository.markPartial(claim.id(), result.runId(), result.failureReason());
			return;
		}
		chunkRepository.markFailed(claim.id(), result.runId(), result.failureReason());
	}

	private RtmsBackfillJobStatus reconcileJobStatus(RtmsBackfillChunkStatusCounts counts) {
		if (counts.problemCount() > 0 || counts.pending() > 0 || counts.running() > 0) {
			return RtmsBackfillJobStatus.PARTIAL;
		}
		return RtmsBackfillJobStatus.COMPLETED;
	}
}
