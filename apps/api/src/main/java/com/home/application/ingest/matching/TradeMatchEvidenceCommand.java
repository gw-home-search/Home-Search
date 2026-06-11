package com.home.application.ingest.matching;

import java.util.List;
import java.util.Objects;

import com.home.domain.ingest.matching.TradeMatchStatus;
import com.home.domain.ingest.matching.TradeMatchPath;
import com.home.domain.ingest.source.IngestSource;
import com.home.domain.trade.RtmsJibunPnu;
import com.home.application.ingest.normalization.RtmsJibunPnuNormalizer;
import com.home.application.ingest.trade.OpenApiTradeItem;

/**
 * raw ingest row와 RTMS match attempt를 연결하는 evidence 저장 command입니다.
 */
public record TradeMatchEvidenceCommand(
	Long rawIngestId,
	String source,
	String rawJibun,
	String normalizedJibun,
	String sggCd,
	String umdCd,
	String landCode,
	String bonbun,
	String bubun,
	String derivedPnu,
	String pnuUnavailableReason,
	String aptSeq,
	String aptName,
	TradeMatchStatus matchStatus,
	String matchPath,
	Long matchedComplexId,
	String matchedComplexPk,
	int candidateCount,
	List<Long> candidateComplexIds,
	String failureReason
) {

	public TradeMatchEvidenceCommand {
		if (rawIngestId == null) {
			throw new IllegalArgumentException("rawIngestId is required");
		}
		source = IngestSource.of(source).value();
		if (matchStatus == null) {
			throw new IllegalArgumentException("matchStatus is required");
		}
		if (candidateCount < 0) {
			throw new IllegalArgumentException("candidateCount must be non-negative");
		}
		rawJibun = trimToNull(rawJibun);
		normalizedJibun = trimToNull(normalizedJibun);
		sggCd = trimToNull(sggCd);
		umdCd = trimToNull(umdCd);
		landCode = trimToNull(landCode);
		bonbun = trimToNull(bonbun);
		bubun = trimToNull(bubun);
		derivedPnu = trimToNull(derivedPnu);
		pnuUnavailableReason = trimToNull(pnuUnavailableReason);
		aptSeq = trimToNull(aptSeq);
		aptName = trimToNull(aptName);
		matchPath = normalizeMatchPath(matchPath);
		matchedComplexPk = trimToNull(matchedComplexPk);
		candidateComplexIds = List.copyOf(Objects.requireNonNullElse(candidateComplexIds, List.of()));
		failureReason = trimToNull(failureReason);
	}

	public static TradeMatchEvidenceCommand from(
		Long rawIngestId,
		String source,
		OpenApiTradeItem item,
		ComplexMatchResult match
	) {
		RtmsJibunPnu pnu = match == null || match.jibunPnu() == null
			? RtmsJibunPnuNormalizer.normalize(item)
			: match.jibunPnu();
		TradeMatchStatus status = match == null ? TradeMatchStatus.UNMATCHED : match.matchStatus();
		return new TradeMatchEvidenceCommand(
			rawIngestId,
			source,
			pnu.rawJibun(),
			pnu.normalizedJibun(),
			pnu.sggCd(),
			pnu.umdCd(),
			pnu.landCode(),
			pnu.bonbun(),
			pnu.bubun(),
			pnu.derivedPnu(),
			pnu.pnuUnavailableReason(),
			item.aptSeq(),
			item.aptName(),
			status,
			match == null ? null : match.matchPath(),
			match == null ? null : match.complexId(),
			match == null ? null : match.complexPk(),
			match == null ? 0 : match.candidateCount(),
			match == null ? List.of() : match.candidateComplexIds(),
			match == null ? "complex matcher returned no result" : match.failureReason()
		);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static String normalizeMatchPath(String value) {
		return hasText(value) ? TradeMatchPath.fromStoredValue(value).storedValue() : null;
	}

	private static String trimToNull(String value) {
		return hasText(value) ? value.trim() : null;
	}
}
