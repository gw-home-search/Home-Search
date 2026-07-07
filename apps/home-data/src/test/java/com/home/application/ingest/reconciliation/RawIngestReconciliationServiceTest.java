package com.home.application.ingest.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.home.application.ingest.raw.RawTradeIngestFailureQuery;
import com.home.application.ingest.raw.RawTradeIngestFailureSummary;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.domain.ingest.raw.RawTradeIngestStatus;

class RawIngestReconciliationServiceTest {

	@Test
	@DisplayName("raw reconciliation service는 limit이 0 이하면 조회와 상태 갱신을 하지 않는다")
	void returnsEmptyWhenLimitIsNotPositive() {
		FakeReconciliationRepository reconciliationRepository = new FakeReconciliationRepository(List.of(
			new RawIngestReconciliationCandidate(101L, 201L)
		));
		FakeRawTradeIngestRepository rawRepository = new FakeRawTradeIngestRepository(List.of());
		RawIngestReconciliationService service = new RawIngestReconciliationService(
			reconciliationRepository,
			rawRepository
		);

		RawIngestReconciliationResult result = service.reconcileReceived(0);

		assertThat(result).isEqualTo(RawIngestReconciliationResult.empty());
		assertThat(reconciliationRepository.requestedLimits).isEmpty();
		assertThat(rawRepository.updatedStatuses).isEmpty();
	}

	@Test
	@DisplayName("raw reconciliation service는 active trade에 연결된 RECEIVED raw를 NORMALIZED로 갱신한다")
	void marksLinkedReceivedRowsAsNormalized() {
		FakeReconciliationRepository reconciliationRepository = new FakeReconciliationRepository(List.of(
			new RawIngestReconciliationCandidate(101L, 201L),
			new RawIngestReconciliationCandidate(102L, 202L)
		));
		FakeRawTradeIngestRepository rawRepository = new FakeRawTradeIngestRepository(List.of(
			raw(101L, "source-101", RawTradeIngestStatus.RECEIVED),
			raw(102L, "source-102", RawTradeIngestStatus.RECEIVED)
		));
		RawIngestReconciliationService service = new RawIngestReconciliationService(
			reconciliationRepository,
			rawRepository
		);

		RawIngestReconciliationResult result = service.reconcileReceived(5);

		assertThat(result.processed()).isEqualTo(2);
		assertThat(result.normalized()).isEqualTo(2);
		assertThat(reconciliationRepository.requestedLimits).containsExactly(5);
		assertThat(rawRepository.updatedStatuses)
			.containsExactly(
				new StatusUpdate(101L, RawTradeIngestStatus.NORMALIZED, null),
				new StatusUpdate(102L, RawTradeIngestStatus.NORMALIZED, null)
			);
	}

	private static RawTradeIngestRecord raw(Long id, String sourceKey, RawTradeIngestStatus status) {
		return new RawTradeIngestRecord(
			id,
			"RTMS",
			sourceKey,
			"11680",
			"202512",
			1,
			"{}",
			"hash-" + sourceKey,
			status,
			null,
			null,
			null
		);
	}

	private static final class FakeReconciliationRepository implements RawIngestReconciliationRepository {
		private final List<RawIngestReconciliationCandidate> candidates;
		private final List<Integer> requestedLimits = new ArrayList<>();

		private FakeReconciliationRepository(List<RawIngestReconciliationCandidate> candidates) {
			this.candidates = candidates;
		}

		@Override
		public List<RawIngestReconciliationCandidate> findReceivedRowsLinkedToActiveTrade(int limit) {
			requestedLimits.add(limit);
			return candidates.stream().limit(limit).toList();
		}
	}

	private static final class FakeRawTradeIngestRepository implements RawTradeIngestRepository {
		private final List<RawTradeIngestRecord> records;
		private final List<StatusUpdate> updatedStatuses = new ArrayList<>();

		private FakeRawTradeIngestRepository(List<RawTradeIngestRecord> records) {
			this.records = new ArrayList<>(records);
		}

		@Override
		public RawTradeIngestRecord save(RawTradeIngestRecord record) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean existsProcessedBySourceAndSourceKeyAndPayloadHashBefore(
			Long rawIngestId,
			String source,
			String sourceKey,
			String payloadHash
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public RawTradeIngestRecord updateStatus(Long id, RawTradeIngestStatus status, String failureReason) {
			updatedStatuses.add(new StatusUpdate(id, status, failureReason));
			return records.stream()
				.filter(record -> record.id().equals(id))
				.findFirst()
				.orElseThrow()
				.withStatus(status, failureReason);
		}

		@Override
		public List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status) {
			return records.stream().filter(record -> record.status() == status).toList();
		}

		@Override
		public List<RawTradeIngestFailureSummary> summarizeFailures(RawTradeIngestFailureQuery query) {
			throw new UnsupportedOperationException();
		}
	}

	private record StatusUpdate(
		Long rawIngestId,
		RawTradeIngestStatus status,
		String failureReason
	) {
	}
}
