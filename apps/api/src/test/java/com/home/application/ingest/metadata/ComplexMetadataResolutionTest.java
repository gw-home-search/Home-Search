package com.home.application.ingest.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexMetadataResolutionTest {

	@Test
	@DisplayName("complex metadata 핵심 필드는 dongCnt, unitCnt, useDate다")
	void criticalMetadataRequiresDongUnitAndUseDate() {
		assertThat(metadata(8, 740, LocalDate.of(2015, 3, 20)).hasAllCriticalFields()).isTrue();
		assertThat(metadata(null, 740, LocalDate.of(2015, 3, 20)).hasAllCriticalFields()).isFalse();
		assertThat(metadata(8, null, LocalDate.of(2015, 3, 20)).hasAllCriticalFields()).isFalse();
		assertThat(metadata(8, 740, null).hasAllCriticalFields()).isFalse();
		assertThat(metadata(0, 740, LocalDate.of(2015, 3, 20)).hasAllCriticalFields()).isFalse();
		assertThat(metadata(8, 0, LocalDate.of(2015, 3, 20)).hasAllCriticalFields()).isFalse();
	}

	@Test
	@DisplayName("resolution classify는 핵심 필드 완성 여부로 RESOLVED와 PARTIAL을 나눈다")
	void classifyByCriticalMetadataCompleteness() {
		assertThat(ComplexMetadataResolution.classify(
			"ODC",
			metadata(8, 740, LocalDate.of(2015, 3, 20))
		).status()).isEqualTo(ComplexMetadataStatus.RESOLVED);

		ComplexMetadataResolution partial = ComplexMetadataResolution.classify(
			"BLD",
			metadata(null, 740, null)
		);

		assertThat(partial.status()).isEqualTo(ComplexMetadataStatus.PARTIAL);
		assertThat(partial.failureKind()).isNull();
	}

	@Test
	@DisplayName("resolution 기본 factory는 구조화된 failure kind를 부여한다")
	void defaultFactoriesAssignStructuredFailureKinds() {
		assertThat(ComplexMetadataResolution.ambiguous("ODC", "ambiguous").failureKind())
			.isEqualTo(ComplexMetadataFailureKind.AMBIGUOUS);
		assertThat(ComplexMetadataResolution.unavailable("ODC", "missing").failureKind())
			.isEqualTo(ComplexMetadataFailureKind.SOURCE_MISSING);
		assertThat(ComplexMetadataResolution.failed("ODC", "timeout").failureKind())
			.isEqualTo(ComplexMetadataFailureKind.TRANSIENT);
	}

	private ComplexMetadata metadata(Integer dongCnt, Integer unitCnt, LocalDate useDate) {
		return new ComplexMetadata(dongCnt, unitCnt, null, null, null, null, null, useDate);
	}
}
