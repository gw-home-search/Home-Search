package com.home.domain.complex.relation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexRelationTypeTest {

	@Test
	@DisplayName("complex relation type은 좌표 예외 해결 대상 관계를 직접 구분한다")
	void relationTypeOwnsCoordinateExceptionRequirement() {
		assertThat(ComplexRelationType.CONCURRENT.requiresCoordinateExceptionResolution()).isTrue();
		assertThat(ComplexRelationType.UNKNOWN.requiresCoordinateExceptionResolution()).isTrue();
		assertThat(ComplexRelationType.SINGLE.requiresCoordinateExceptionResolution()).isFalse();
		assertThat(ComplexRelationType.REDEVELOPED.requiresCoordinateExceptionResolution()).isFalse();
		assertThat(ComplexRelationType.MASTER_UPDATE.requiresCoordinateExceptionResolution()).isFalse();
		assertThat(ComplexRelationType.UNRELATED.requiresCoordinateExceptionResolution()).isFalse();
	}
}
