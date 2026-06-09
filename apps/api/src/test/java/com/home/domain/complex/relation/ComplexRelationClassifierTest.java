package com.home.domain.complex.relation;

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
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.HIGH);
	}

	@Test
	@DisplayName("complex relation classifier는 거래 기간이 겹치면 CONCURRENT로 분류한다")
	void classifiesConcurrentComplexesWhenTradeSpansOverlap() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "A", "2024-01-01", "2025-06-01", "2010-01-01"),
			span(502L, "APT-502", "B", "2025-01-01", "2025-12-01", "2015-01-01")
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.CONCURRENT);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.HIGH);
	}

	@Test
	@DisplayName("complex relation classifier는 거래 span 경계일이 같으면 CONCURRENT로 분류한다")
	void classifiesConcurrentWhenTradeSpanBoundaryTouches() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "A", "2024-01-01", "2025-01-01", null),
			span(502L, "APT-502", "B", "2025-01-01", "2025-12-01", null)
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.CONCURRENT);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.HIGH);
	}

	@Test
	@DisplayName("complex relation classifier는 준공일 격차와 순차 거래 span이 충분하면 REDEVELOPED로 분류한다")
	void classifiesRedevelopedComplexesWhenUseDateEvidenceIsStrong() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "Old", "2016-01-01", "2018-01-01", "1995-01-01"),
			span(502L, "APT-502", "New", "2020-01-01", "2025-01-01", "2020-01-01")
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.REDEVELOPED);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.HIGH);
		assertThat(classification.reason()).contains("use_date");
	}

	@Test
	@DisplayName("complex relation classifier는 준공일 없는 충분한 거래 공백을 낮은 신뢰도 REDEVELOPED로 분류한다")
	void classifiesRedevelopedComplexesWithLowConfidenceWhenOnlyTradeGapIsAvailable() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "Old", "2016-01-01", "2018-01-01", null),
			span(502L, "APT-502", "New", "2020-01-01", "2025-01-01", null)
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.REDEVELOPED);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.LOW);
		assertThat(classification.reason()).contains("heuristic");
	}

	@Test
	@DisplayName("complex relation classifier는 준공일 없는 정확히 12개월 거래 공백을 낮은 신뢰도 REDEVELOPED로 분류한다")
	void classifiesRedevelopedWithLowConfidenceWhenTradeGapEqualsThreshold() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "Old", "2023-01-01", "2024-01-01", null),
			span(502L, "APT-502", "New", "2025-01-01", "2025-12-01", null)
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.REDEVELOPED);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.LOW);
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

	@Test
	@DisplayName("complex relation classifier는 complex가 없으면 UNKNOWN으로 남긴다")
	void keepsUnknownWhenComplexIsEmpty() {
		var classification = classifier.classify(List.of());

		assertThat(classification.type()).isEqualTo(ComplexRelationType.UNKNOWN);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.NONE);
	}

	@Test
	@DisplayName("complex relation classifier는 작은 거래 공백과 단순 준공일 증가만으로 REDEVELOPED를 확정하지 않는다")
	void keepsUnknownWhenOnlyWeakUseDateTieBreakExists() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "A", "2024-01-01", "2024-12-01", "2010-01-01"),
			span(502L, "APT-502", "B", "2025-06-01", "2025-12-01", "2015-01-01")
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.UNKNOWN);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.NONE);
		assertThat(classification.reason()).contains("use_date evidence is weak");
	}

	@Test
	@DisplayName("complex relation classifier는 거래 1건짜리 span만으로 REDEVELOPED를 분류하지 않는다")
	void keepsUnknownWhenTradeGapUsesTooFewTrades() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "A", "2016-01-01", "2016-01-01", 1, null),
			span(502L, "APT-502", "B", "2020-01-01", "2020-01-01", 1, null)
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.UNKNOWN);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.NONE);
		assertThat(classification.reason()).contains("sample too small");
	}

	@Test
	@DisplayName("complex relation classifier는 거래 기간 overlap을 준공일보다 우선해 CONCURRENT로 분류한다")
	void classifiesConcurrentWhenTradeSpansOverlapEvenWithUseDateRedevelopmentSignal() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "Old", "2024-01-01", "2025-06-01", "1990-01-01"),
			span(502L, "APT-502", "New", "2025-01-01", "2025-12-01", "2020-01-01")
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.CONCURRENT);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.HIGH);
	}

	@Test
	@DisplayName("complex relation classifier는 3개 이상이라도 준공일이 근접하면 공존 가능성으로 UNKNOWN을 남긴다")
	void keepsUnknownForThreeOrMoreComplexesWhenUseDatesAreClose() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "A", "2010-01-01", "2012-01-01", "2015-01-01"),
			span(502L, "APT-502", "B", "2014-01-01", "2016-01-01", "2018-01-01"),
			span(503L, "APT-503", "C", "2018-01-01", "2025-01-01", "2020-01-01")
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.UNKNOWN);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.NONE);
		assertThat(classification.reason()).contains("multiple complex generations");
	}

	@Test
	@DisplayName("complex relation classifier는 전 세대 준공일 격차가 순차면 3개 이상도 REDEVELOPED로 분류한다")
	void classifiesMultiGenerationRedevelopmentWhenUseDateChainIsSequential() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "A", "2010-01-01", "2012-01-01", "1980-01-01"),
			span(502L, "APT-502", "B", "2014-01-01", "2016-01-01", "2000-01-01"),
			span(503L, "APT-503", "C", "2018-01-01", "2025-01-01", "2020-01-01")
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.REDEVELOPED);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.HIGH);
		assertThat(classification.reason()).contains("all generations");
	}

	@Test
	@DisplayName("complex relation classifier는 준공일이 없어도 전 구간 거래공백이 충분하면 3개 이상도 REDEVELOPED(LOW)로 분류한다")
	void classifiesMultiGenerationRedevelopmentByTradeGapWhenUseDateAbsent() {
		var classification = classifier.classify(List.of(
			span(501L, "APT-501", "A", "2008-01-01", "2010-01-01", null),
			span(502L, "APT-502", "B", "2014-01-01", "2016-01-01", null),
			span(503L, "APT-503", "C", "2020-01-01", "2025-01-01", null)
		));

		assertThat(classification.type()).isEqualTo(ComplexRelationType.REDEVELOPED);
		assertThat(classification.confidence()).isEqualTo(ComplexRelationConfidence.LOW);
		assertThat(classification.reason()).contains("trade gap");
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

	private ComplexTradeSpan span(
		Long complexId,
		String aptSeq,
		String name,
		String firstDeal,
		String lastDeal,
		long tradeCount,
		String useDate
	) {
		return new ComplexTradeSpan(
			complexId,
			aptSeq,
			name,
			LocalDate.parse(firstDeal),
			LocalDate.parse(lastDeal),
			tradeCount,
			useDate == null ? null : LocalDate.parse(useDate)
		);
	}
}
