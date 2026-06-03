package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.home.application.ingest.ComplexMatchResult;
import com.home.application.ingest.NormalizedTradeCommand;
import com.home.application.ingest.NormalizedTradeRepository;
import com.home.application.ingest.OpenApiTradeItem;
import com.home.application.ingest.RawIngestReconciliationCandidate;
import com.home.application.ingest.RawIngestReconciliationRepository;
import com.home.application.ingest.RawIngestReconciliationService;
import com.home.application.ingest.RawTradeIngestFailureQuery;
import com.home.application.ingest.RawTradeIngestFailureSummary;
import com.home.application.ingest.RawTradeIngestRecord;
import com.home.application.ingest.RawTradeIngestRepository;
import com.home.application.ingest.RawTradeIngestStatus;
import com.home.application.ingest.TradeMatchEvidenceRepository;
import com.home.application.ingest.TradeMatchRematchService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class IngestRecoveryRunnerTest {

	@Test
	@DisplayName("raw reconciliation runner는 설정된 batch size로 reconciliation service를 실행한다")
	void rawReconciliationRunnerUsesConfiguredBatchSize() throws Exception {
		FakeReconciliationRepository reconciliationRepository = new FakeReconciliationRepository();
		RawIngestReconciliationService service = new RawIngestReconciliationService(
			reconciliationRepository,
			new EmptyRawRepository()
		);
		RawIngestReconciliationRunner runner = new RawIngestReconciliationRunner(service, 25);

		runner.run(new DefaultApplicationArguments());

		assertThat(reconciliationRepository.requestedLimits).containsExactly(25);
		assertThat(runner.getOrder()).isEqualTo(ApplicationRunnerOrders.RAW_INGEST_RECONCILIATION);
	}

	@Test
	@DisplayName("trade match rematch runner는 설정된 batch size로 rematch service를 실행한다")
	void tradeMatchRematchRunnerUsesConfiguredBatchSize() throws Exception {
		RecordingRawRepository rawRepository = new RecordingRawRepository();
		TradeMatchRematchService service = new TradeMatchRematchService(
			rawRepository,
			new NoopNormalizedRepository(),
			item -> ComplexMatchResult.matched(501L, "COMPLEX-501", "APT_SEQ"),
			TradeMatchEvidenceRepository.noop(),
			raw -> Optional.of(new OpenApiTradeItem(
				"101",
				"Sample Apartment",
				"APT-501",
				"125,000",
				15,
				12,
				2025,
				84.93,
				12,
				"140-1",
				"11680",
				"10300",
				"{}"
			))
		);
		TradeMatchRematchRunner runner = new TradeMatchRematchRunner(service, 3);

		runner.run(new DefaultApplicationArguments());

		assertThat(rawRepository.findByStatusCalls).containsExactly(RawTradeIngestStatus.MATCH_FAILED);
	}

	private static final class FakeReconciliationRepository implements RawIngestReconciliationRepository {
		private final List<Integer> requestedLimits = new ArrayList<>();

		@Override
		public List<RawIngestReconciliationCandidate> findReceivedRowsLinkedToActiveTrade(int limit) {
			requestedLimits.add(limit);
			return List.of();
		}
	}

	private static class EmptyRawRepository implements RawTradeIngestRepository {
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
			throw new UnsupportedOperationException();
		}

		@Override
		public List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status) {
			return List.of();
		}

		@Override
		public List<RawTradeIngestFailureSummary> summarizeFailures(RawTradeIngestFailureQuery query) {
			throw new UnsupportedOperationException();
		}
	}

	private static final class RecordingRawRepository extends EmptyRawRepository {
		private final List<RawTradeIngestStatus> findByStatusCalls = new ArrayList<>();

		@Override
		public List<RawTradeIngestRecord> findByStatus(RawTradeIngestStatus status) {
			findByStatusCalls.add(status);
			return List.of();
		}
	}

	private static final class NoopNormalizedRepository implements NormalizedTradeRepository {
		@Override
		public boolean existsBySourceAndSourceKey(String source, String sourceKey) {
			return false;
		}

		@Override
		public boolean cancelBySourceAndSourceKey(String source, String sourceKey, Long rawIngestId) {
			return false;
		}

		@Override
		public boolean insertIfAbsent(NormalizedTradeCommand command) {
			return true;
		}
	}
}
