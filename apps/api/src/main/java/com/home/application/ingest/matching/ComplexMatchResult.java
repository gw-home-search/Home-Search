package com.home.application.ingest.matching;

import java.util.List;
import java.util.Objects;

import com.home.domain.ingest.matching.TradeMatchStatus;
import com.home.domain.trade.RtmsJibunPnu;

public record ComplexMatchResult(
	Long complexId,
	String complexPk,
	String matchPath,
	TradeMatchStatus matchStatus,
	String failureReason,
	RtmsJibunPnu jibunPnu,
	int candidateCount,
	List<Long> candidateComplexIds
) {

	public static ComplexMatchResult matched(Long complexId, String complexPk, String matchPath) {
		return matched(complexId, complexPk, matchPath, TradeMatchStatus.MATCHED, null, 1, List.of(complexId), null);
	}

	public static ComplexMatchResult matched(
		Long complexId,
		String complexPk,
		String matchPath,
		TradeMatchStatus matchStatus,
		RtmsJibunPnu jibunPnu,
		int candidateCount,
		List<Long> candidateComplexIds,
		String failureReason
	) {
		if (complexId == null) {
			throw new IllegalArgumentException("complexId is required");
		}
		if (!hasText(complexPk)) {
			throw new IllegalArgumentException("complexPk is required");
		}
		if (!hasText(matchPath)) {
			throw new IllegalArgumentException("matchPath is required");
		}
		TradeMatchStatus status = matchStatus == null ? TradeMatchStatus.MATCHED : matchStatus;
		return new ComplexMatchResult(
			complexId,
			complexPk.trim(),
			matchPath.trim(),
			status,
			hasText(failureReason) ? failureReason.trim() : null,
			jibunPnu,
			candidateCount,
			List.copyOf(Objects.requireNonNullElse(candidateComplexIds, List.of(complexId)))
		);
	}

	public static ComplexMatchResult failed(String failureReason) {
		return failed(TradeMatchStatus.UNMATCHED, failureReason, null, 0, List.of());
	}

	public static ComplexMatchResult failed(
		TradeMatchStatus matchStatus,
		String failureReason,
		RtmsJibunPnu jibunPnu,
		int candidateCount,
		List<Long> candidateComplexIds
	) {
		TradeMatchStatus status = matchStatus == null ? TradeMatchStatus.UNMATCHED : matchStatus;
		return new ComplexMatchResult(
			null,
			null,
			null,
			status,
			hasText(failureReason) ? failureReason.trim() : "complex match failed",
			jibunPnu,
			candidateCount,
			List.copyOf(Objects.requireNonNullElse(candidateComplexIds, List.of()))
		);
	}

	public boolean matched() {
		return complexId != null;
	}

	public String derivedPnu() {
		return jibunPnu == null ? null : jibunPnu.derivedPnu();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
