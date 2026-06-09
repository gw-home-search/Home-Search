package com.home.domain.complex.relation;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public class ComplexRelationClassifier {

	private static final int DEFAULT_REDEVELOPMENT_GAP_MONTHS = 12;
	private static final int DEFAULT_MIN_TRADE_COUNT_FOR_REDEVELOPMENT = 2;
	private static final int DEFAULT_USE_DATE_GAP_YEARS = 10;

	private final int redevelopmentGapMonths;
	private final int minTradeCountForRedevelopment;
	private final int useDateGapYears;

	public ComplexRelationClassifier() {
		this(DEFAULT_REDEVELOPMENT_GAP_MONTHS, DEFAULT_MIN_TRADE_COUNT_FOR_REDEVELOPMENT, DEFAULT_USE_DATE_GAP_YEARS);
	}

	public ComplexRelationClassifier(int redevelopmentGapMonths) {
		this(redevelopmentGapMonths, DEFAULT_MIN_TRADE_COUNT_FOR_REDEVELOPMENT, DEFAULT_USE_DATE_GAP_YEARS);
	}

	public ComplexRelationClassifier(
		int redevelopmentGapMonths,
		int minTradeCountForRedevelopment,
		int useDateGapYears
	) {
		if (redevelopmentGapMonths < 0) {
			throw new IllegalArgumentException("redevelopmentGapMonths must be non-negative");
		}
		if (minTradeCountForRedevelopment < 1) {
			throw new IllegalArgumentException("minTradeCountForRedevelopment must be positive");
		}
		if (useDateGapYears < 0) {
			throw new IllegalArgumentException("useDateGapYears must be non-negative");
		}
		this.redevelopmentGapMonths = redevelopmentGapMonths;
		this.minTradeCountForRedevelopment = minTradeCountForRedevelopment;
		this.useDateGapYears = useDateGapYears;
	}

	public ComplexRelationClassification classify(List<ComplexTradeSpan> input) {
		List<ComplexTradeSpan> spans = sorted(input);
		if (spans.isEmpty()) {
			return unknown(spans, "complex count is 0");
		}
		if (spans.size() == 1) {
			return new ComplexRelationClassification(
				ComplexRelationType.SINGLE,
				spans,
				"complex count is 1",
				ComplexRelationConfidence.HIGH
			);
		}
		List<ComplexTradeSpan> tradeSpans = spans.stream()
			.filter(ComplexTradeSpan::hasTradeSpan)
			.toList();
		if (tradeSpans.size() < spans.size()) {
			return unknown(spans, "trade span unavailable");
		}
		if (hasOverlappingTradeSpan(tradeSpans)) {
			return new ComplexRelationClassification(
				ComplexRelationType.CONCURRENT,
				spans,
				"active trade spans overlap",
				ComplexRelationConfidence.HIGH
			);
		}
		if (!hasEnoughTradeSamplesForRedevelopment(tradeSpans)) {
			return unknown(spans, "trade span sample too small for redevelopment classification");
		}
		if (spans.size() > 2) {
			if (hasSequentialUseDateChain(tradeSpans)) {
				return new ComplexRelationClassification(
					ComplexRelationType.REDEVELOPED,
					spans,
					"use_date gaps across all generations support sequential redevelopment",
					ComplexRelationConfidence.HIGH
				);
			}
			if (hasWeakUseDateEvidence(tradeSpans)) {
				return unknown(spans, "multiple complex generations require manual review");
			}
			if (hasSequentialTradeGapChain(tradeSpans)) {
				return new ComplexRelationClassification(
					ComplexRelationType.REDEVELOPED,
					spans,
					"sequential trade gaps across all generations support redevelopment; trade gap is heuristic",
					ComplexRelationConfidence.LOW
				);
			}
			return unknown(spans, "multiple complex generations require manual review");
		}
		if (hasStrongUseDateEvidence(tradeSpans)) {
			return new ComplexRelationClassification(
				ComplexRelationType.REDEVELOPED,
				spans,
				"use_date gap and sequential trade spans support redevelopment",
				ComplexRelationConfidence.HIGH
			);
		}
		if (hasWeakUseDateEvidence(tradeSpans)) {
			return unknown(spans, "sequential trade gap inconclusive and use_date evidence is weak");
		}
		if (hasRedevelopmentGap(tradeSpans)) {
			return new ComplexRelationClassification(
				ComplexRelationType.REDEVELOPED,
				spans,
				"sequential trade spans exceed redevelopment gap; trade gap is heuristic",
				ComplexRelationConfidence.LOW
			);
		}
		return unknown(spans, "sequential gap inconclusive");
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

	private boolean hasSequentialTradeGapChain(List<ComplexTradeSpan> spans) {
		for (int i = 1; i < spans.size(); i++) {
			LocalDate previousLast = spans.get(i - 1).lastDeal();
			LocalDate nextFirst = spans.get(i).firstDeal();
			if (previousLast.plusMonths(redevelopmentGapMonths).isAfter(nextFirst)) {
				return false;
			}
		}
		return true;
	}

	private boolean hasEnoughTradeSamplesForRedevelopment(List<ComplexTradeSpan> spans) {
		return spans.stream()
			.allMatch(span -> span.tradeCount() >= minTradeCountForRedevelopment);
	}

	private boolean hasStrongUseDateEvidence(List<ComplexTradeSpan> spans) {
		for (int i = 1; i < spans.size(); i++) {
			LocalDate previousUseDate = spans.get(i - 1).useDate();
			LocalDate nextUseDate = spans.get(i).useDate();
			if (
				previousUseDate != null
					&& nextUseDate != null
					&& !previousUseDate.plusYears(useDateGapYears).isAfter(nextUseDate)
			) {
				return true;
			}
		}
		return false;
	}

	private boolean hasSequentialUseDateChain(List<ComplexTradeSpan> spans) {
		for (int i = 1; i < spans.size(); i++) {
			LocalDate previousUseDate = spans.get(i - 1).useDate();
			LocalDate nextUseDate = spans.get(i).useDate();
			if (
				previousUseDate == null
					|| nextUseDate == null
					|| previousUseDate.plusYears(useDateGapYears).isAfter(nextUseDate)
			) {
				return false;
			}
		}
		return true;
	}

	private boolean hasWeakUseDateEvidence(List<ComplexTradeSpan> spans) {
		for (int i = 1; i < spans.size(); i++) {
			LocalDate previousUseDate = spans.get(i - 1).useDate();
			LocalDate nextUseDate = spans.get(i).useDate();
			if (previousUseDate != null && nextUseDate != null) {
				return true;
			}
		}
		return false;
	}

	private ComplexRelationClassification unknown(List<ComplexTradeSpan> spans, String reason) {
		return new ComplexRelationClassification(
			ComplexRelationType.UNKNOWN,
			spans,
			reason,
			ComplexRelationConfidence.NONE
		);
	}
}
