package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.home.application.ingest.matching.ComplexMatchResult;
import com.home.application.ingest.normalization.NormalizedTradeCommand;
import com.home.application.ingest.normalization.NormalizedTradeRepository;
import com.home.application.ingest.trade.OpenApiTradeItem;
import com.home.application.ingest.reconciliation.RawIngestReconciliationCandidate;
import com.home.application.ingest.reconciliation.RawIngestReconciliationRepository;
import com.home.application.ingest.reconciliation.RawIngestReconciliationService;
import com.home.application.ingest.raw.RawTradeIngestFailureQuery;
import com.home.application.ingest.raw.RawTradeIngestFailureSummary;
import com.home.application.ingest.raw.RawTradeIngestRecord;
import com.home.application.ingest.raw.RawTradeIngestRepository;
import com.home.domain.ingest.raw.RawTradeIngestStatus;
import com.home.application.ingest.matching.TradeMatchEvidenceRepository;
import com.home.application.ingest.matching.TradeMatchRematchService;
import com.home.infrastructure.ApplicationRunnerOrders;
import com.home.infrastructure.persistence.ingest.matching.TradeMatchRematchRunner;
import com.home.infrastructure.persistence.ingest.normalization.JdbcTradePartitionMaintenanceRepository;
import com.home.infrastructure.persistence.ingest.normalization.TradePartitionMaintenanceRunner;
import com.home.infrastructure.persistence.ingest.raw.RawIngestReconciliationRunner;

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
	@DisplayName("raw reconciliation runner는 DB가 없으면 reconciliation을 건너뛴다")
	void rawReconciliationRunnerSkipsWhenDatabaseIsUnavailable() throws Exception {
		FakeReconciliationRepository reconciliationRepository = new FakeReconciliationRepository();
		RawIngestReconciliationService service = new RawIngestReconciliationService(
			reconciliationRepository,
			new EmptyRawRepository()
		);
		RawIngestReconciliationRunner runner = new RawIngestReconciliationRunner(() -> service, 25, () -> false);

		runner.run(new DefaultApplicationArguments());

		assertThat(reconciliationRepository.requestedLimits).isEmpty();
	}

	@Test
	@DisplayName("trade partition maintenance runner는 DB가 없으면 partition 생성을 건너뛴다")
	void tradePartitionMaintenanceRunnerSkipsWhenDatabaseIsUnavailable() throws Exception {
		JdbcTradePartitionMaintenanceRepository repository = mock(JdbcTradePartitionMaintenanceRepository.class);
		TradePartitionMaintenanceRunner runner = new TradePartitionMaintenanceRunner(
			() -> repository,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC),
			5,
			() -> false
		);

		runner.run(new DefaultApplicationArguments());

		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("trade partition maintenance runner는 DB가 있으면 설정된 연도 범위로 partition을 보장한다")
	void tradePartitionMaintenanceRunnerEnsuresConfiguredYearRange() throws Exception {
		JdbcTradePartitionMaintenanceRepository repository = mock(JdbcTradePartitionMaintenanceRepository.class);
		TradePartitionMaintenanceRunner runner = new TradePartitionMaintenanceRunner(
			() -> repository,
			Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC),
			5,
			() -> true
		);

		runner.run(new DefaultApplicationArguments());

		verify(repository).ensureYearlyPartitions(2026, 2031);
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
