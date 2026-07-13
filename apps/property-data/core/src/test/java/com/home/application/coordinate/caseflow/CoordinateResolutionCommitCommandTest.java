package com.home.application.coordinate.caseflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import com.home.application.coordinate.display.ResolvedDisplayCoordinate;
import com.home.domain.coordinate.ComplexCoordinateCaseStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoordinateResolutionCommitCommandTest {

	@Test
	@DisplayName("resolved case는 coordinate가 필요하고 unresolved case는 coordinate를 포함할 수 없다")
	void enforcesCoordinateAndTerminalStatusConsistency() {
		assertThatThrownBy(() -> new CoordinateResolutionCommitCommand(
			List.of(),
			new ComplexCoordinateCaseUpdate(1001L, ComplexCoordinateCaseStatus.RESOLVED, "resolved")
		)).isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> new CoordinateResolutionCommitCommand(
			List.of(coordinate()),
			new ComplexCoordinateCaseUpdate(1001L, ComplexCoordinateCaseStatus.FAILED, "failed")
		)).isInstanceOf(IllegalArgumentException.class);
	}

	private ResolvedDisplayCoordinate coordinate() {
		return new ResolvedDisplayCoordinate(
			501L,
			9001L,
			new BigDecimal("37.5010000"),
			new BigDecimal("127.0010000"),
			"BUILDING_FOOTPRINT",
			90,
			"matched"
		);
	}
}
