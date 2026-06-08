package com.home.application.ingest.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexMetadataEnrichmentServiceTest {

	@Test
	@DisplayName("metadata enrichment service는 건별 결과와 다음 재시도 시각을 저장하고 예외가 나도 계속 처리한다")
	void enrichesPendingComplexesAndContinuesAfterRowFailure() {
		FakeRepository repository = new FakeRepository(List.of(
			lookup(501, 0),
			lookup(502, 0),
			lookup(503, 1),
			lookup(504, 0)
		));
		ComplexMetadataEnrichmentService service = new ComplexMetadataEnrichmentService(repository, lookup -> {
			if (lookup.complexId() == 501) {
				return ComplexMetadataResolution.classify("ODC", metadata());
			}
			if (lookup.complexId() == 502) {
				return ComplexMetadataResolution.ambiguous("ODC", "ODC PNU candidate ambiguous");
			}
			if (lookup.complexId() == 503) {
				return ComplexMetadataResolution.classify("BLD", partialMetadata());
			}
			throw new IllegalStateException("temporary 503 serviceKey=sample-value");
		});

		ComplexMetadataEnrichmentResult result = service.enrichPending(10);

		assertThat(result).isEqualTo(new ComplexMetadataEnrichmentResult(4, 1, 1, 1, 0, 1));
		assertThat(repository.saved).extracting(SavedResolution::complexId)
			.containsExactly(501L, 502L, 503L, 504L);
		assertThat(repository.saved).extracting(saved -> saved.resolution().status())
			.containsExactly(
				ComplexMetadataStatus.RESOLVED,
				ComplexMetadataStatus.AMBIGUOUS,
				ComplexMetadataStatus.PARTIAL,
				ComplexMetadataStatus.FAILED
			);
		assertThat(repository.saved.get(0).nextAttemptAt()).isNull();
		assertThat(repository.saved.get(1).nextAttemptAt()).isNull();
		assertThat(repository.saved.get(2).nextAttemptAt()).isAfter(Instant.now());
		assertThat(repository.saved.get(3).nextAttemptAt()).isAfter(Instant.now());
		assertThat(repository.saved.get(3).resolution().failureReason()).contains("serviceKey=[REDACTED]");
	}

	@Test
	@DisplayName("metadata enrichment service는 외부 client가 미설정이면 pending row를 조회하거나 오염시키지 않는다")
	void skipsPendingLookupWhenClientIsNotConfigured() {
		FakeRepository repository = new FakeRepository(List.of(lookup(501, 0)));
		ComplexMetadataEnrichmentClient client = new ComplexMetadataEnrichmentClient() {
			@Override
			public boolean isConfigured() {
				return false;
			}

			@Override
			public ComplexMetadataResolution resolve(ComplexMetadataLookup lookup) {
				throw new AssertionError("metadata lookup must not run when client is not configured");
			}
		};
		ComplexMetadataEnrichmentService service = new ComplexMetadataEnrichmentService(repository, client);

		ComplexMetadataEnrichmentResult result = service.enrichPending(10);

		assertThat(result).isEqualTo(ComplexMetadataEnrichmentResult.empty());
		assertThat(repository.findPendingCalls).isZero();
		assertThat(repository.saved).isEmpty();
	}

	private ComplexMetadataLookup lookup(long complexId, int attempts) {
		return new ComplexMetadataLookup(
			complexId,
			"APT-%d".formatted(complexId),
			"Sample Apartment %d".formatted(complexId),
			"1168010300101400001",
			"Sample address",
			attempts
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

	private ComplexMetadata partialMetadata() {
		return new ComplexMetadata(null, 740, new BigDecimal("12345.67"), null, null, null, null, null);
	}

	private static final class FakeRepository implements ComplexMetadataEnrichmentRepository {

		private final List<ComplexMetadataLookup> pending;
		private final List<SavedResolution> saved = new ArrayList<>();
		private int findPendingCalls;

		private FakeRepository(List<ComplexMetadataLookup> pending) {
			this.pending = pending;
		}

		@Override
		public List<ComplexMetadataLookup> findPending(int limit) {
			findPendingCalls++;
			return pending.stream().limit(limit).toList();
		}

		@Override
		public void saveResolution(Long complexId, ComplexMetadataResolution resolution, Instant nextAttemptAt) {
			saved.add(new SavedResolution(complexId, resolution, nextAttemptAt));
		}
	}

	private record SavedResolution(
		Long complexId,
		ComplexMetadataResolution resolution,
		Instant nextAttemptAt
	) {
	}
}
