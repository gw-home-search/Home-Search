package com.home.infrastructure.persistence.coordinate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.home.application.coordinate.ComplexCoordinateExceptionService;
import com.home.application.coordinate.ComplexCoordinateReadinessRepository;
import com.home.application.coordinate.ComplexCoordinateReadinessResult;
import com.home.application.coordinate.ComplexCoordinateReadinessService;
import com.home.application.coordinate.ComplexDisplayCoordinateProjectionService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class ComplexCoordinateReadinessRunnerTest {

	@Test
	@DisplayName("coordinate readiness runner는 configured limits로 readiness service를 실행한다")
	void runnerExecutesReadinessServiceWithConfiguredLimits() throws Exception {
		FakeReadinessService service = new FakeReadinessService();
		ComplexCoordinateReadinessRunner runner = new ComplexCoordinateReadinessRunner(service, 3, 5, 7);

		runner.run(new DefaultApplicationArguments());

		assertThat(service.stageLimit).isEqualTo(3);
		assertThat(service.resolveLimit).isEqualTo(5);
		assertThat(service.projectLimit).isEqualTo(7);
	}

	@Test
	@DisplayName("coordinate readiness runner는 RTMS one-shot ingest 이후 실행된다")
	void runnerRunsAfterRtmsOneShotIngest() {
		ComplexCoordinateReadinessRunner runner = new ComplexCoordinateReadinessRunner(
			new FakeReadinessService(),
			3,
			5,
			7
		);

		assertThat(runner.getOrder()).isEqualTo(ApplicationRunnerOrders.COORDINATE_READINESS);
		assertThat(runner.getOrder()).isGreaterThan(ApplicationRunnerOrders.RTMS_ONE_SHOT_INGEST);
		assertThat(runner.getOrder()).isGreaterThan(ApplicationRunnerOrders.TRADE_MATCH_REMATCH);
	}

	private static final class FakeReadinessService extends ComplexCoordinateReadinessService {

		private int stageLimit;
		private int resolveLimit;
		private int projectLimit;

		private FakeReadinessService() {
			super(
				new ComplexCoordinateExceptionService(
					new NoopCoordinateExceptionRepository(),
					parcelId -> List.of(),
					new com.home.application.complex.ComplexRelationClassifier()
				),
				new NoopReadinessRepository(),
				new ComplexDisplayCoordinateProjectionService(new NoopProjectionRepository())
			);
		}

		@Override
		public ComplexCoordinateReadinessResult prepare(int stageLimit, int resolveLimit, int projectLimit) {
			this.stageLimit = stageLimit;
			this.resolveLimit = resolveLimit;
			this.projectLimit = projectLimit;
			return ComplexCoordinateReadinessResult.empty();
		}
	}

	private static final class NoopReadinessRepository implements ComplexCoordinateReadinessRepository {

		@Override
		public List<Long> findPendingCaseParcelIds(int limit) {
			return List.of();
		}

		@Override
		public void markCaseFailed(Long parcelId, String reason) {
		}
	}

	private static final class NoopProjectionRepository implements com.home.application.coordinate.ComplexDisplayCoordinateProjectionRepository {

		@Override
		public List<com.home.application.coordinate.ComplexDisplayCoordinateProjectionTarget> findProjectionTargets(int limit) {
			return List.of();
		}

		@Override
		public void saveDisplayCoordinate(com.home.application.coordinate.ComplexDisplayCoordinateCommand command) {
		}
	}

	private static final class NoopCoordinateExceptionRepository implements com.home.application.coordinate.ComplexCoordinateExceptionRepository {

		@Override
		public List<com.home.application.coordinate.ComplexCoordinateCaseCandidate> findExceptionCaseCandidates(int limit) {
			return List.of();
		}

		@Override
		public void saveCaseUpdate(com.home.application.coordinate.ComplexCoordinateCaseUpdate update) {
		}

		@Override
		public java.util.Optional<com.home.application.coordinate.ComplexCoordinateParcelTargets> findParcelTargets(Long parcelId) {
			return java.util.Optional.empty();
		}

		@Override
		public List<com.home.application.coordinate.BuildingFootprintCandidate> findBuildingFootprintsByPnu(String pnu) {
			return List.of();
		}

		@Override
		public void saveResolvedDisplayCoordinate(com.home.application.coordinate.ResolvedDisplayCoordinate coordinate) {
		}
	}
}
