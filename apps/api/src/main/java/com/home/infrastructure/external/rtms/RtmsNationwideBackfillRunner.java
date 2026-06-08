package com.home.infrastructure.external.rtms;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

import com.home.application.ingest.backfill.RtmsBackfillChunkClaim;
import com.home.application.ingest.backfill.RtmsBackfillChunkRepository;
import com.home.application.ingest.backfill.RtmsBackfillChunkStatus;
import com.home.application.ingest.backfill.RtmsBackfillChunkStatusCounts;
import com.home.application.ingest.backfill.RtmsBackfillJobRecord;
import com.home.application.ingest.backfill.RtmsBackfillJobRepository;
import com.home.application.ingest.backfill.RtmsBackfillJobStatus;

class RtmsNationwideBackfillRunner {

	private final Supplier<RtmsBackfillJobRepository> jobRepositorySupplier;
	private final Supplier<RtmsBackfillChunkRepository> chunkRepositorySupplier;
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
		this(() -> jobRepository, () -> chunkRepository, chunkExecutor, clock, options);
	}

	RtmsNationwideBackfillRunner(
		Supplier<RtmsBackfillJobRepository> jobRepositorySupplier,
		Supplier<RtmsBackfillChunkRepository> chunkRepositorySupplier,
		RtmsBackfillChunkExecutor chunkExecutor,
		Clock clock,
		RtmsNationwideBackfillOptions options
	) {
		this.jobRepositorySupplier = Objects.requireNonNull(jobRepositorySupplier);
		this.chunkRepositorySupplier = Objects.requireNonNull(chunkRepositorySupplier);
		this.chunkExecutor = Objects.requireNonNull(chunkExecutor);
		this.clock = Objects.requireNonNull(clock);
		this.options = Objects.requireNonNull(options);
	}

	RtmsNationwideBackfillReport run(RtmsNationwideBackfillPlan plan) {
		Objects.requireNonNull(plan, "plan is required");
		RtmsBackfillJobRepository jobRepository = jobRepositorySupplier.get();
		RtmsBackfillChunkRepository chunkRepository = chunkRepositorySupplier.get();
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
			markChunk(chunkRepository, claim, result);
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

	private void markChunk(
		RtmsBackfillChunkRepository chunkRepository,
		RtmsBackfillChunkClaim claim,
		RtmsBackfillChunkExecutionResult result
	) {
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
