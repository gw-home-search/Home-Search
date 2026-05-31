package com.home.application.complex;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public class ComplexRelationClassifier {

	private static final int DEFAULT_REDEVELOPMENT_GAP_MONTHS = 12;

	private final int redevelopmentGapMonths;

	public ComplexRelationClassifier() {
		this(DEFAULT_REDEVELOPMENT_GAP_MONTHS);
	}

	public ComplexRelationClassifier(int redevelopmentGapMonths) {
		if (redevelopmentGapMonths < 0) {
			throw new IllegalArgumentException("redevelopmentGapMonths must be non-negative");
		}
		this.redevelopmentGapMonths = redevelopmentGapMonths;
	}

	public ComplexRelationClassification classify(List<ComplexTradeSpan> input) {
		List<ComplexTradeSpan> spans = sorted(input);
		if (spans.size() <= 1) {
			return new ComplexRelationClassification(ComplexRelationType.SINGLE, spans, "complex count <= 1");
		}
		List<ComplexTradeSpan> tradeSpans = spans.stream()
			.filter(ComplexTradeSpan::hasTradeSpan)
			.toList();
		if (tradeSpans.size() < spans.size()) {
			return new ComplexRelationClassification(ComplexRelationType.UNKNOWN, spans, "trade span unavailable");
		}
		if (hasOverlappingTradeSpan(tradeSpans)) {
			return new ComplexRelationClassification(ComplexRelationType.CONCURRENT, spans, "active trade spans overlap");
		}
		if (hasRedevelopmentGap(tradeSpans)) {
			return new ComplexRelationClassification(
				ComplexRelationType.REDEVELOPED,
				spans,
				"sequential trade spans exceed redevelopment gap"
			);
		}
		if (hasUseDateTieBreak(tradeSpans)) {
			return new ComplexRelationClassification(
				ComplexRelationType.REDEVELOPED,
				spans,
				"use_date supports sequential redevelopment"
			);
		}
		return new ComplexRelationClassification(ComplexRelationType.UNKNOWN, spans, "sequential gap inconclusive");
	}

	private List<ComplexTradeSpan> sorted(List<ComplexTradeSpan> input) {
		return (input == null ? List.<ComplexTradeSpan>of() : input).stream()
			.sorted(Comparator
				.comparing(ComplexTradeSpan::firstDeal, Comparator.nullsLast(Comparator.naturalOrder()))
				.thenComparing(ComplexTradeSpan::complexId, Comparator.nullsLast(Comparator.naturalOrder())))
			.toList();
	}

	private boolean hasOverlappingTradeSpan(List<ComplexTradeSpan> spans) {
		for (int i = 0; i < spans.size(); i++) {
			for (int j = i + 1; j < spans.size(); j++) {
				if (overlaps(spans.get(i), spans.get(j))) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean overlaps(ComplexTradeSpan left, ComplexTradeSpan right) {
		return !left.lastDeal().isBefore(right.firstDeal())
			&& !right.lastDeal().isBefore(left.firstDeal());
	}

	private boolean hasRedevelopmentGap(List<ComplexTradeSpan> spans) {
		for (int i = 1; i < spans.size(); i++) {
			LocalDate previousLast = spans.get(i - 1).lastDeal();
			LocalDate nextFirst = spans.get(i).firstDeal();
			if (!previousLast.plusMonths(redevelopmentGapMonths).isAfter(nextFirst)) {
				return true;
			}
		}
		return false;
	}

	private boolean hasUseDateTieBreak(List<ComplexTradeSpan> spans) {
		for (int i = 1; i < spans.size(); i++) {
			LocalDate previousUseDate = spans.get(i - 1).useDate();
			LocalDate nextUseDate = spans.get(i).useDate();
			if (previousUseDate != null && nextUseDate != null && nextUseDate.isAfter(previousUseDate)) {
				return true;
			}
		}
		return false;
	}
}
