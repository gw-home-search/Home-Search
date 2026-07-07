package com.home.application.coordinate.override;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.home.domain.coordinate.CoordinatePendingReason;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordinateOverrideAdminServiceTest {

	@Test
	@DisplayName("coordinate override admin service는 기본 limit, 최대 limit, offset을 정규화한다")
	void normalizesPendingListLimit() {
		FakeRepository repository = new FakeRepository();
		CoordinateOverrideAdminService service = new CoordinateOverrideAdminService(repository);

		service.findPendingComplexes(null);
		assertThat(repository.lastLimit).isEqualTo(50);
		assertThat(repository.lastOffset).isZero();

		service.findPendingComplexes(500);
		assertThat(repository.lastLimit).isEqualTo(200);

		service.findPendingComplexes(25, 50);
		assertThat(repository.lastLimit).isEqualTo(25);
		assertThat(repository.lastOffset).isEqualTo(50);
	}

	@Test
	@DisplayName("coordinate override admin service는 invalid limit과 offset을 거부한다")
	void rejectsInvalidPendingPagingParameters() {
		CoordinateOverrideAdminService service = new CoordinateOverrideAdminService(new FakeRepository());

		assertThatThrownBy(() -> service.findPendingComplexes(0))
			.isInstanceOf(InvalidCoordinateOverrideException.class)
			.hasMessageContaining("limit");
		assertThatThrownBy(() -> service.findPendingComplexes(25, -1))
			.isInstanceOf(InvalidCoordinateOverrideException.class)
			.hasMessageContaining("offset");
	}

	@Test
	@DisplayName("coordinate override admin service는 전체 pending summary를 조회한다")
	void findsPendingSummary() {
		FakeRepository repository = new FakeRepository();
		CoordinateOverrideAdminService service = new CoordinateOverrideAdminService(repository);

		CoordinatePendingSummary summary = service.findPendingSummary();

		assertThat(summary.totalCount()).isEqualTo(3L);
		assertThat(summary.count(CoordinatePendingReason.PNU_COORDINATE_MISSING)).isEqualTo(1L);
		assertThat(summary.count(CoordinatePendingReason.SAME_PNU_MULTI_COMPLEX)).isEqualTo(2L);
	}

	@Test
	@DisplayName("coordinate override admin service는 path PNU를 정규화해 승인 command에 사용한다")
	void approvesWithNormalizedPathPnu() {
		FakeRepository repository = new FakeRepository();
		CoordinateOverrideAdminService service = new CoordinateOverrideAdminService(repository);
		CoordinateOverrideApprovalCommand command = new CoordinateOverrideApprovalCommand(
			"0000000000000000000",
			new BigDecimal("37.5123000"),
			new BigDecimal("127.0456000"),
			"operator verified missing coordinate",
			"test-operator"
		);

		CoordinateOverrideApprovalResult result = service.approve(" 1168010300101400001 ", command);

		assertThat(repository.approvedCommands)
			.singleElement()
			.satisfies(saved -> {
				assertThat(saved.pnu()).isEqualTo("1168010300101400001");
				assertThat(saved.latitude()).isEqualByComparingTo(command.latitude());
				assertThat(saved.longitude()).isEqualByComparingTo(command.longitude());
				assertThat(saved.reason()).isEqualTo(command.reason());
				assertThat(saved.approvedBy()).isEqualTo(command.approvedBy());
			});
		assertThat(result.pnu()).isEqualTo("1168010300101400001");
		assertThat(result.parcelUpdated()).isTrue();
	}

	@Test
	@DisplayName("coordinate override admin service는 invalid PNU 승인을 거부한다")
	void rejectsInvalidPnu() {
		CoordinateOverrideAdminService service = new CoordinateOverrideAdminService(new FakeRepository());
		CoordinateOverrideApprovalCommand command = new CoordinateOverrideApprovalCommand(
			"invalid",
			new BigDecimal("37.5123000"),
			new BigDecimal("127.0456000"),
			null,
			"test-operator"
		);

		assertThatThrownBy(() -> service.approve("invalid", command))
			.isInstanceOf(InvalidCoordinateOverrideException.class)
			.hasMessageContaining("pnu");
	}

	private static final class FakeRepository implements CoordinateOverrideAdminRepository {

		private int lastLimit;
		private int lastOffset;
		private final List<CoordinateOverrideApprovalCommand> approvedCommands = new ArrayList<>();

		@Override
		public List<CoordinatePendingComplex> findPendingComplexes(int limit, int offset) {
			lastLimit = limit;
			lastOffset = offset;
			return List.of();
		}

		@Override
		public CoordinatePendingSummary findPendingSummary() {
			return new CoordinatePendingSummary(3L, 1L, 2L, 0L);
		}

		@Override
		public CoordinateOverrideApprovalResult approve(CoordinateOverrideApprovalCommand command) {
			approvedCommands.add(command);
			return new CoordinateOverrideApprovalResult(
				command.pnu(),
				command.latitude(),
				command.longitude(),
				true
			);
		}
	}
}
