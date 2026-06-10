package com.home.infrastructure.external.rtms;

import java.util.Objects;
import java.util.function.Supplier;

import com.home.application.ingest.run.RtmsIngestRunRepository;

final class RtmsIngestRunRepositoryReference {

	private final Supplier<RtmsIngestRunRepository> supplier;

	private RtmsIngestRunRepositoryReference(Supplier<RtmsIngestRunRepository> supplier) {
		this.supplier = Objects.requireNonNull(supplier);
	}

	static RtmsIngestRunRepositoryReference of(RtmsIngestRunRepository repository) {
		Objects.requireNonNull(repository);
		return new RtmsIngestRunRepositoryReference(() -> repository);
	}

	static RtmsIngestRunRepositoryReference lazy(Supplier<RtmsIngestRunRepository> supplier) {
		return new RtmsIngestRunRepositoryReference(supplier);
	}

	RtmsIngestRunRepository get() {
		return Objects.requireNonNull(supplier.get(), "RtmsIngestRunRepository is required");
	}
}
