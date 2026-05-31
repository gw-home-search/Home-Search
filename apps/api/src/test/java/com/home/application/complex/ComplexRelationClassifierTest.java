package com.home.application.complex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ComplexRelationClassifierTest {

	private final ComplexRelationClassifier classifier = new ComplexRelationClassifier();

	@Test
	@DisplayName("complex relation classifier는 단일 complex를 SINGLE로 분류한다")
	void classifiesSingleComplex() {
		var classification = classifier.classify(List.of(span(501L, "APT-501", "A", "2025-01-01", "2025-12-01", null)));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.SINGLE);
	}

	@Test
	@DisplayName("complex relation classifier는 거래 기간이 겹치면 CONCURRENT로 분류한다")
	void classifiesConcurrentComplexesWhenTradeSpansOverlap() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "A", "2024-01-01", "2025-06-01", "2010-01-01"),
			span(502L, "APT-502", "B", "2025-01-01", "2025-12-01", "2015-01-01")
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.CONCURRENT);
	}

	@Test
	@DisplayName("complex relation classifier는 충분한 거래 공백이 있으면 REDEVELOPED로 분류한다")
	void classifiesRedevelopedComplexesWhenSequentialGapIsLargeEnough() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "Old", "2016-01-01", "2018-01-01", "1995-01-01"),
			span(502L, "APT-502", "New", "2020-01-01", "2025-01-01", "2020-01-01")
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.REDEVELOPED);
	}

	@Test
	@DisplayName("complex relation classifier는 다중 complex 중 거래 span이 없으면 UNKNOWN으로 남긴다")
	void keepsUnknownWhenAnyTradeSpanIsUnavailable() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "A", "2024-01-01", "2025-01-01", null),
			new ComplexTradeSpan(502L, "APT-502", "B", null, null, 0, null)
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.UNKNOWN);
	}

	private ComplexTradeSpan span(
		Long complexId,
		String aptSeq,
		String name,
		String firstDeal,
		String lastDeal,
		String useDate
	) {
		return new ComplexTradeSpan(
			complexId,
			aptSeq,
			name,
			LocalDate.parse(firstDeal),
			LocalDate.parse(lastDeal),
			3,
			useDate == null ? null : LocalDate.parse(useDate)
		);
	}
}
