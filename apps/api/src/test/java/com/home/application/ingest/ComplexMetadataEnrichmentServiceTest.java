package com.home.application.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexMetadataEnrichmentServiceTest {

	@Test
	@DisplayName("metadata enrichment service는 건별 결과를 저장하고 예외가 나도 다음 complex를 계속 처리한다")
	void enrichesPendingComplexesAndContinuesAfterRowFailure() {
		FakeRepository repository = new FakeRepository(List.of(
			lookup(501),
			lookup(502),
			lookup(503)
		));
		ComplexMetadataEnrichmentService service = new ComplexMetadataEnrichmentService(repository, lookup -> {
			if (lookup.complexId() == 501) {
				return ComplexMetadataResolution.resolved("ODC", metadata());
			}
			if (lookup.complexId() == 502) {
				return ComplexMetadataResolution.ambiguous("ODC", "ODC PNU candidate ambiguous");
			}
			throw new IllegalStateException("temporary 503 serviceKey=sample-value");
		});

		ComplexMetadataEnrichmentResult result = service.enrichPending(10);

		assertThat(result).isEqualTo(new ComplexMetadataEnrichmentResult(3, 1, 1, 0, 1));
		assertThat(repository.saved).extracting(SavedResolution::complexId)
			.containsExactly(501L, 502L, 503L);
		assertThat(repository.saved).extracting(saved -> saved.resolution().status())
			.containsExactly(ComplexMetadataStatus.RESOLVED, ComplexMetadataStatus.AMBIGUOUS, ComplexMetadataStatus.FAILED);
		assertThat(repository.saved.get(2).resolution().failureReason()).contains("serviceKey=[REDACTED]");
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

	private ComplexMetadata metadata() {
		return new ComplexMetadata(
			8,
			740,
			new BigDecimal("12345.67"),
			new BigDecimal("2345.67"),
			new BigDecimal("98765.43"),
			new BigDecimal("22.50"),
			new BigDecimal("199.80"),
			LocalDate.of(2015, 3, 20)
		);
	}

	private static final class FakeRepository implements ComplexMetadataEnrichmentRepository {

		private final List<ComplexMetadataLookup> pending;
		private final List<SavedResolution> saved = new ArrayList<>();

		private FakeRepository(List<ComplexMetadataLookup> pending) {
			this.pending = pending;
		}

		@Override
		public List<ComplexMetadataLookup> findPending(int limit) {
			return pending.stream().limit(limit).toList();
		}

		@Override
		public void saveResolution(Long complexId, ComplexMetadataResolution resolution) {
			saved.add(new SavedResolution(complexId, resolution));
		}
	}

	private record SavedResolution(
		Long complexId,
		ComplexMetadataResolution resolution
	) {
	}
}
