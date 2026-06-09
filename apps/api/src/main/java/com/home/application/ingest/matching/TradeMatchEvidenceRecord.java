package com.home.application.ingest.matching;

import java.time.Instant;
import java.util.List;

import com.home.domain.ingest.matching.TradeMatchStatus;

public record TradeMatchEvidenceRecord(
	Long id,
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
	String failureReason,
	Instant createdAt
) {
}
