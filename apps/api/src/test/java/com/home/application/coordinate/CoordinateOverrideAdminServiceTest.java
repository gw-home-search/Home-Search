package com.home.application.coordinate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordinateOverrideAdminServiceTest {

	@Test
	@DisplayName("coordinate override admin service는 기본 limit과 최대 limit을 보정한다")
	void normalizesPendingListLimit() {
		FakeRepository repository = new FakeRepository();
		CoordinateOverrideAdminService service = new CoordinateOverrideAdminService(repository);

		service.findPendingComplexes(null);
		assertThat(repository.lastLimit).isEqualTo(50);

		service.findPendingComplexes(0);
		assertThat(repository.lastLimit).isEqualTo(50);

		service.findPendingComplexes(500);
		assertThat(repository.lastLimit).isEqualTo(200);
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
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("pnu");
	}

	private static final class FakeRepository implements CoordinateOverrideAdminRepository {

		private int lastLimit;
		private final List<CoordinateOverrideApprovalCommand> approvedCommands = new ArrayList<>();

		@Override
		public List<CoordinatePendingComplex> findPendingComplexes(int limit) {
			lastLimit = limit;
			return List.of();
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
