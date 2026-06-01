package com.home.infrastructure.external.complex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.home.application.ingest.ComplexMetadata;
import com.home.application.ingest.ComplexMetadataEnrichmentRepository;
import com.home.application.ingest.ComplexMetadataEnrichmentService;
import com.home.application.ingest.ComplexMetadataLookup;
import com.home.application.ingest.ComplexMetadataResolution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class ComplexMetadataEnrichmentRunnerTest {

	@Test
	@DisplayName("metadata enrichment runner는 configured batch size로 pending enrichment를 실행한다")
	void runnerExecutesPendingEnrichmentWithConfiguredBatchSize() throws Exception {
		FakeRepository repository = new FakeRepository(List.of(
			lookup(501),
			lookup(502)
		));
		ComplexMetadataEnrichmentService service = new ComplexMetadataEnrichmentService(
			repository,
			lookup -> ComplexMetadataResolution.partial("ODC", ComplexMetadata.empty())
		);
		ComplexMetadataEnrichmentRunner runner = new ComplexMetadataEnrichmentRunner(service, 1);

		runner.run(new DefaultApplicationArguments());

		assertThat(repository.savedComplexIds).containsExactly(501L);
	}

	@Test
	@DisplayName("metadata enrichment scheduler는 configured batch size로 due enrichment를 실행한다")
	void schedulerExecutesDueEnrichmentWithConfiguredBatchSize() {
		FakeRepository repository = new FakeRepository(List.of(
			lookup(501),
			lookup(502)
		));
		ComplexMetadataEnrichmentService service = new ComplexMetadataEnrichmentService(
			repository,
			lookup -> ComplexMetadataResolution.partial("ODC", ComplexMetadata.empty())
		);
		ComplexMetadataEnrichmentScheduler scheduler = new ComplexMetadataEnrichmentScheduler(service, 1);

		scheduler.runDue();

		assertThat(repository.savedComplexIds).containsExactly(501L);
	}

	private ComplexMetadataLookup lookup(long complexId) {
		return new ComplexMetadataLookup(
			complexId,
			"APT-%d".formatted(complexId),
			"Sample Apartment %d".formatted(complexId),
			"1168010300101400001",
			"Sample address"
		);
	}

	private static final class FakeRepository implements ComplexMetadataEnrichmentRepository {

		private final List<ComplexMetadataLookup> pending;
		private final List<Long> savedComplexIds = new ArrayList<>();

		private FakeRepository(List<ComplexMetadataLookup> pending) {
			this.pending = pending;
		}

		@Override
		public List<ComplexMetadataLookup> findPending(int limit) {
			return pending.stream().limit(limit).toList();
		}

		@Override
		public void saveResolution(Long complexId, ComplexMetadataResolution resolution, Instant nextAttemptAt) {
			savedComplexIds.add(complexId);
		}
	}
}
