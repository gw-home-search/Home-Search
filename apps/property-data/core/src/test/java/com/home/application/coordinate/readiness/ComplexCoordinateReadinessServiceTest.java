package com.home.application.coordinate.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.home.application.coordinate.caseflow.ComplexCoordinateCaseCandidate;
import com.home.domain.coordinate.ComplexCoordinateCaseStatus;
import com.home.application.coordinate.caseflow.ComplexCoordinateCaseUpdate;
import com.home.application.coordinate.caseflow.ComplexCoordinateExceptionRepository;
import com.home.application.coordinate.caseflow.ComplexCoordinateExceptionResult;
import com.home.application.coordinate.caseflow.ComplexCoordinateExceptionService;
import com.home.application.coordinate.caseflow.ComplexCoordinateResolutionResult;
import com.home.application.coordinate.display.ComplexDisplayCoordinateCommand;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionRepository;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionResult;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionService;
import com.home.application.coordinate.display.ComplexDisplayCoordinateProjectionTarget;
import com.home.application.coordinate.display.ResolvedDisplayCoordinate;
import com.home.application.coordinate.footprint.BuildingFootprintCandidate;
import com.home.application.coordinate.footprint.BuildingFootprintImportCandidate;
import com.home.application.coordinate.footprint.BuildingFootprintSource;
import com.home.application.coordinate.identity.ComplexCoordinateIdentityVerifier;
import com.home.application.coordinate.identity.ComplexCoordinateParcelTargets;
import com.home.domain.coordinate.CoordinateIdentityBlockingPolicy;

class ComplexCoordinateReadinessServiceTest {

	@Test
	@DisplayName("coordinate readiness는 case stage, pending resolve, display coordinate projection을 순서대로 실행한다")
	void runsStageResolveAndProjectionInOrder() {
		FakeReadinessRepository repository = new FakeReadinessRepository(List.of(1001L, 1002L, 1003L));
		FakeCoordinateExceptionService exceptionService = new FakeCoordinateExceptionService();
		FakeProjectionService projectionService = new FakeProjectionService();
		ComplexCoordinateReadinessService service = new ComplexCoordinateReadinessService(
			exceptionService,
			repository,
			projectionService
		);

		ComplexCoordinateReadinessResult result = service.prepare(5, 10, 20);

		assertThat(exceptionService.stageLimit).isEqualTo(5);
		assertThat(repository.pendingLimit).isEqualTo(10);
		assertThat(projectionService.projectLimit).isEqualTo(20);
		assertThat(exceptionService.resolvedParcelIds).containsExactly(1001L, 1002L, 1003L);
		assertThat(repository.failedParcelIds).containsExactly(1003L);
		assertThat(result.staged()).isEqualTo(2);
		assertThat(result.pending()).isEqualTo(1);
		assertThat(result.skipped()).isEqualTo(1);
		assertThat(result.resolved()).isEqualTo(1);
		assertThat(result.ambiguous()).isEqualTo(1);
		assertThat(result.unavailable()).isZero();
		assertThat(result.failed()).isEqualTo(1);
		assertThat(result.projectedBuildingFootprint()).isEqualTo(2);
		assertThat(result.projectedParcelFallback()).isEqualTo(3);
		assertThat(result.projectionSkipped()).isEqualTo(4);
		assertThat(result.projectionMissing()).isEqualTo(5);
	}

	@Test
	@DisplayName("coordinate readiness는 limit이 1보다 작으면 해당 단계를 건너뛴다")
	void skipsStepsWithNonPositiveLimits() {
		FakeReadinessRepository repository = new FakeReadinessRepository(List.of(1001L));
		FakeCoordinateExceptionService exceptionService = new FakeCoordinateExceptionService();
		FakeProjectionService projectionService = new FakeProjectionService();
		ComplexCoordinateReadinessService service = new ComplexCoordinateReadinessService(
			exceptionService,
			repository,
			projectionService
		);

		ComplexCoordinateReadinessResult result = service.prepare(0, 0, 0);

		assertThat(result).isEqualTo(ComplexCoordinateReadinessResult.empty());
		assertThat(exceptionService.stageLimit).isZero();
		assertThat(exceptionService.resolvedParcelIds).isEmpty();
		assertThat(repository.pendingLimit).isZero();
		assertThat(projectionService.projectLimit).isZero();
	}

	@Test
	@DisplayName("coordinate readiness는 retry가 활성화되면 retryable 케이스를 backoff 후보로 재시도한다")
	void retriesCasesWhenRetryEnabled() {
		FakeReadinessRepository repository = new FakeReadinessRepository(List.of());
		repository.retryableParcelIds = List.of(1001L);
		FakeCoordinateExceptionService exceptionService = new FakeCoordinateExceptionService();
		FakeProjectionService projectionService = new FakeProjectionService();
		ComplexCoordinateReadinessService service = new ComplexCoordinateReadinessService(
			exceptionService,
			repository,
			projectionService,
			10,
			java.time.Duration.ofHours(6)
		);

		ComplexCoordinateReadinessResult result = service.prepare(0, 0, 0);

		assertThat(repository.retryLimit).isEqualTo(10);
		assertThat(exceptionService.resolvedParcelIds).containsExactly(1001L);
		assertThat(result.retried()).isEqualTo(1);
		assertThat(result.resolved()).isEqualTo(1);
	}

	@Test
	@DisplayName("coordinate readiness는 retry가 비활성(기본)이면 retryable 후보를 조회하지 않는다")
	void doesNotRetryWhenRetryDisabled() {
		FakeReadinessRepository repository = new FakeReadinessRepository(List.of());
		repository.retryableParcelIds = List.of(1001L);
		ComplexCoordinateReadinessService service = new ComplexCoordinateReadinessService(
			new FakeCoordinateExceptionService(),
			repository,
			new FakeProjectionService()
		);

		ComplexCoordinateReadinessResult result = service.prepare(0, 0, 0);

		assertThat(repository.retryLimit).isZero();
		assertThat(result.retried()).isZero();
	}

	@Test
	@DisplayName("coordinate readiness는 retry 설정이 음수이면 시작하지 않는다")
	void rejectsNegativeRetryConfiguration() {
		assertThatThrownBy(() -> new ComplexCoordinateReadinessService(
			new FakeCoordinateExceptionService(),
			new FakeReadinessRepository(List.of()),
			new FakeProjectionService(),
			-1,
			java.time.Duration.ZERO
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("retryLimit");

		assertThatThrownBy(() -> new ComplexCoordinateReadinessService(
			new FakeCoordinateExceptionService(),
			new FakeReadinessRepository(List.of()),
			new FakeProjectionService(),
			1,
			java.time.Duration.ofMillis(-1)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("retryAfter");
	}

	private static final class FakeReadinessRepository implements ComplexCoordinateReadinessRepository {

		private final List<Long> pendingParcelIds;
		private int pendingLimit;
		private final List<Long> failedParcelIds = new ArrayList<>();
		private List<Long> retryableParcelIds = List.of();
		private int retryLimit;

		private FakeReadinessRepository(List<Long> pendingParcelIds) {
			this.pendingParcelIds = pendingParcelIds;
		}

		@Override
		public List<Long> findPendingCaseParcelIds(int limit) {
			pendingLimit = limit;
			return pendingParcelIds.stream().limit(limit).toList();
		}

		@Override
		public List<Long> findRetryableCaseParcelIds(int limit, java.time.Instant retryBefore) {
			retryLimit = limit;
			return retryableParcelIds.stream().limit(limit).toList();
		}

		@Override
		public void markCaseFailed(Long parcelId, String reason) {
			failedParcelIds.add(parcelId);
		}
	}

	private static final class FakeCoordinateExceptionService extends ComplexCoordinateExceptionService {

			private int stageLimit;
			private final List<Long> resolvedParcelIds = new ArrayList<>();

			private FakeCoordinateExceptionService() {
				super(
					new NoopCoordinateExceptionRepository(),
					parcelId -> List.of(),
					new com.home.domain.complex.relation.ComplexRelationClassifier(),
					ComplexCoordinateIdentityVerifier.trusting(),
					BuildingFootprintSource.unavailable(),
					CoordinateIdentityBlockingPolicy.degradeUnavailableAndFailed()
				);
			}

		@Override
		public ComplexCoordinateExceptionResult stageExceptionCases(int limit) {
			stageLimit = limit;
			return new ComplexCoordinateExceptionResult(2, 1, 0, 0, 0, 0, 1);
		}

		@Override
		public ComplexCoordinateResolutionResult resolveExceptionCase(Long parcelId) {
			resolvedParcelIds.add(parcelId);
			if (parcelId == 1001L) {
				return new ComplexCoordinateResolutionResult(parcelId, ComplexCoordinateCaseStatus.RESOLVED, 2, "resolved");
			}
			if (parcelId == 1002L) {
				return new ComplexCoordinateResolutionResult(parcelId, ComplexCoordinateCaseStatus.AMBIGUOUS, 0, "ambiguous");
			}
			throw new IllegalStateException("boom");
		}
	}

	private static final class FakeProjectionService extends ComplexDisplayCoordinateProjectionService {

		private int projectLimit;

		private FakeProjectionService() {
			super(new InMemoryProjectionRepository());
		}

		@Override
		public ComplexDisplayCoordinateProjectionResult project(int limit) {
			projectLimit = limit;
			return new ComplexDisplayCoordinateProjectionResult(14, 2, 3, 4, 5);
		}
	}

	private static final class InMemoryProjectionRepository implements ComplexDisplayCoordinateProjectionRepository {

		@Override
		public List<ComplexDisplayCoordinateProjectionTarget> findProjectionTargets(int limit) {
			return List.of();
		}

		@Override
		public void saveDisplayCoordinate(ComplexDisplayCoordinateCommand command) {
		}
	}

	private static final class NoopCoordinateExceptionRepository implements ComplexCoordinateExceptionRepository {

		@Override
		public List<ComplexCoordinateCaseCandidate> findExceptionCaseCandidates(int limit) {
			return List.of();
		}

		@Override
		public void saveCaseUpdate(ComplexCoordinateCaseUpdate update) {
		}

		@Override
		public java.util.Optional<ComplexCoordinateParcelTargets> findParcelTargets(Long parcelId) {
			return java.util.Optional.empty();
		}

		@Override
		public List<BuildingFootprintCandidate> findBuildingFootprintsByPnu(String pnu) {
			return List.of();
		}

		@Override
		public void saveBuildingFootprints(List<BuildingFootprintImportCandidate> footprints) {
		}

		@Override
		public void saveResolvedDisplayCoordinate(ResolvedDisplayCoordinate coordinate) {
		}
	}
}
