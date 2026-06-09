package com.home.application.coordinate.display;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.home.domain.coordinate.ComplexCoordinateCaseStatus;

class ComplexDisplayCoordinateProjectionServiceTest {

	@Test
	@DisplayName("complex 표시 좌표 projection은 건물 좌표를 우선하고 없으면 parcel fallback을 생성한다")
	void projectsBuildingFootprintFirstThenParcelFallback() {
		InMemoryProjectionRepository repository = new InMemoryProjectionRepository(List.of(
			targetWithResolvedBuilding(501L),
			singleComplexFallbackTarget(502L),
			multiComplexAmbiguousFallbackTarget(503L),
			multiComplexUnstagedFallbackTarget(506L),
			existingBuildingFootprintTarget(504L),
			targetWithoutParcelCoordinate(505L)
		));
		ComplexDisplayCoordinateProjectionService service = new ComplexDisplayCoordinateProjectionService(repository);

		ComplexDisplayCoordinateProjectionResult result = service.project(10);

		assertThat(result.processed()).isEqualTo(6);
		assertThat(result.buildingFootprint()).isEqualTo(1);
		assertThat(result.parcelFallback()).isEqualTo(3);
		assertThat(result.skipped()).isEqualTo(1);
		assertThat(result.missing()).isEqualTo(1);
		assertThat(repository.saved)
			.extracting(
				ComplexDisplayCoordinateCommand::complexId,
				ComplexDisplayCoordinateCommand::coordinateSource,
				ComplexDisplayCoordinateCommand::confidence,
				ComplexDisplayCoordinateCommand::latitude,
				ComplexDisplayCoordinateCommand::longitude
			)
			.containsExactly(
				tuple(501L, "BUILDING_FOOTPRINT", 95, bd("37.5010000"), bd("127.0010000")),
				tuple(502L, "PARCEL_FALLBACK", 70, bd("37.5123000"), bd("127.0456000")),
				tuple(503L, "PARCEL_FALLBACK", 40, bd("37.5124000"), bd("127.0457000")),
				tuple(506L, "PARCEL_FALLBACK", 50, bd("37.5126000"), bd("127.0459000"))
			);
	}

	@Test
	@DisplayName("projection limit이 1보다 작으면 조회 없이 빈 결과를 반환한다")
	void ignoresNonPositiveLimit() {
		InMemoryProjectionRepository repository = new InMemoryProjectionRepository(List.of(singleComplexFallbackTarget(502L)));
		ComplexDisplayCoordinateProjectionService service = new ComplexDisplayCoordinateProjectionService(repository);

		ComplexDisplayCoordinateProjectionResult result = service.project(0);

		assertThat(result).isEqualTo(ComplexDisplayCoordinateProjectionResult.empty());
		assertThat(repository.findCalled).isFalse();
		assertThat(repository.saved).isEmpty();
	}

	private static ComplexDisplayCoordinateProjectionTarget targetWithResolvedBuilding(Long complexId) {
		return new ComplexDisplayCoordinateProjectionTarget(
			complexId,
			1001L,
			bd("37.5123000"),
			bd("127.0456000"),
			1,
			null,
			null,
			9001L,
			bd("37.5010000"),
			bd("127.0010000"),
			95,
			"resolved building link"
		);
	}

	private static ComplexDisplayCoordinateProjectionTarget singleComplexFallbackTarget(Long complexId) {
		return new ComplexDisplayCoordinateProjectionTarget(
			complexId,
			1002L,
			bd("37.5123000"),
			bd("127.0456000"),
			1,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	private static ComplexDisplayCoordinateProjectionTarget multiComplexAmbiguousFallbackTarget(Long complexId) {
		return new ComplexDisplayCoordinateProjectionTarget(
			complexId,
			1003L,
			bd("37.5124000"),
			bd("127.0457000"),
			2,
			ComplexCoordinateCaseStatus.AMBIGUOUS,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	private static ComplexDisplayCoordinateProjectionTarget multiComplexUnstagedFallbackTarget(Long complexId) {
		return new ComplexDisplayCoordinateProjectionTarget(
			complexId,
			1006L,
			bd("37.5126000"),
			bd("127.0459000"),
			2,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	private static ComplexDisplayCoordinateProjectionTarget existingBuildingFootprintTarget(Long complexId) {
		return new ComplexDisplayCoordinateProjectionTarget(
			complexId,
			1004L,
			bd("37.5125000"),
			bd("127.0458000"),
			1,
			null,
			"BUILDING_FOOTPRINT",
			null,
			null,
			null,
			null,
			null
		);
	}

	private static ComplexDisplayCoordinateProjectionTarget targetWithoutParcelCoordinate(Long complexId) {
		return new ComplexDisplayCoordinateProjectionTarget(
			complexId,
			1005L,
			null,
			null,
			1,
			null,
			null,
			null,
			null,
			null,
			null,
			null
		);
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	private static final class InMemoryProjectionRepository implements ComplexDisplayCoordinateProjectionRepository {

		private final List<ComplexDisplayCoordinateProjectionTarget> targets;
		private final List<ComplexDisplayCoordinateCommand> saved = new ArrayList<>();
		private boolean findCalled;

		private InMemoryProjectionRepository(List<ComplexDisplayCoordinateProjectionTarget> targets) {
			this.targets = targets;
		}

		@Override
		public List<ComplexDisplayCoordinateProjectionTarget> findProjectionTargets(int limit) {
			findCalled = true;
			return targets.stream().limit(limit).toList();
		}

		@Override
		public void saveDisplayCoordinate(ComplexDisplayCoordinateCommand command) {
			saved.add(command);
		}
	}
}
