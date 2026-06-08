package com.home.infrastructure.persistence.coordinate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.home.application.coordinate.caseflow.ComplexCoordinateExceptionService;
import com.home.application.coordinate.readiness.ComplexCoordinateReadinessRepository;
import com.home.application.coordinate.readiness.ComplexCoordinateReadinessResult;
import com.home.application.coordinate.readiness.ComplexCoordinateReadinessService;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import com.home.application.coordinate.caseflow.ComplexCoordinateCaseCandidate;
import com.home.application.coordinate.caseflow.ComplexCoordinateCaseUpdate;
import com.home.application.coordinate.caseflow.ComplexCoordinateExceptionRepository;
import com.home.application.coordinate.display.ComplexDisplayCoordinateCommand;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionRepository;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionTarget;
import com.home.application.coordinate.display.ResolvedDisplayCoordinate;
import com.home.application.coordinate.footprint.BuildingFootprintCandidate;
import com.home.application.coordinate.footprint.BuildingFootprintImportCandidate;
import com.home.application.coordinate.identity.ComplexCoordinateParcelTargets;

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

	private static final class NoopProjectionRepository implements com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionRepository {

		@Override
		public List<com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionTarget> findProjectionTargets(int limit) {
			return List.of();
		}

		@Override
		public void saveDisplayCoordinate(com.home.application.coordinate.display.ComplexDisplayCoordinateCommand command) {
		}
	}

	private static final class NoopCoordinateExceptionRepository implements com.home.application.coordinate.caseflow.ComplexCoordinateExceptionRepository {

		@Override
		public List<com.home.application.coordinate.caseflow.ComplexCoordinateCaseCandidate> findExceptionCaseCandidates(int limit) {
			return List.of();
		}

		@Override
		public void saveCaseUpdate(com.home.application.coordinate.caseflow.ComplexCoordinateCaseUpdate update) {
		}

		@Override
		public java.util.Optional<com.home.application.coordinate.identity.ComplexCoordinateParcelTargets> findParcelTargets(Long parcelId) {
			return java.util.Optional.empty();
		}

		@Override
		public List<com.home.application.coordinate.footprint.BuildingFootprintCandidate> findBuildingFootprintsByPnu(String pnu) {
			return List.of();
		}

		@Override
		public void saveBuildingFootprints(
			List<com.home.application.coordinate.footprint.BuildingFootprintImportCandidate> footprints
		) {
		}

		@Override
		public void saveResolvedDisplayCoordinate(com.home.application.coordinate.display.ResolvedDisplayCoordinate coordinate) {
		}
	}
}
