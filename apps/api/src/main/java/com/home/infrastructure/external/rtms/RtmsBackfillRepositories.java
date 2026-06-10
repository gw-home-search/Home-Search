package com.home.infrastructure.external.rtms;

import java.util.Objects;
import java.util.function.Supplier;

import com.home.application.ingest.backfill.RtmsBackfillChunkRepository;
import com.home.application.ingest.backfill.RtmsBackfillJobRepository;

final class RtmsBackfillRepositories {

	private final Supplier<RtmsBackfillJobRepository> jobRepositorySupplier;
	private final Supplier<RtmsBackfillChunkRepository> chunkRepositorySupplier;

	private RtmsBackfillRepositories(
		Supplier<RtmsBackfillJobRepository> jobRepositorySupplier,
		Supplier<RtmsBackfillChunkRepository> chunkRepositorySupplier
	) {
		this.jobRepositorySupplier = Objects.requireNonNull(jobRepositorySupplier);
		this.chunkRepositorySupplier = Objects.requireNonNull(chunkRepositorySupplier);
	}

	static RtmsBackfillRepositories of(
		RtmsBackfillJobRepository jobRepository,
		RtmsBackfillChunkRepository chunkRepository
	) {
		Objects.requireNonNull(jobRepository);
		Objects.requireNonNull(chunkRepository);
		return new RtmsBackfillRepositories(() -> jobRepository, () -> chunkRepository);
	}

	static RtmsBackfillRepositories lazy(
		Supplier<RtmsBackfillJobRepository> jobRepositorySupplier,
		Supplier<RtmsBackfillChunkRepository> chunkRepositorySupplier
	) {
		return new RtmsBackfillRepositories(jobRepositorySupplier, chunkRepositorySupplier);
	}

	RtmsBackfillJobRepository jobRepository() {
		return Objects.requireNonNull(jobRepositorySupplier.get(), "RtmsBackfillJobRepository is required");
	}

	RtmsBackfillChunkRepository chunkRepository() {
		return Objects.requireNonNull(chunkRepositorySupplier.get(), "RtmsBackfillChunkRepository is required");
	}
}
