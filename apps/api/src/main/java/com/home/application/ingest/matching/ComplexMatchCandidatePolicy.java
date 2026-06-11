package com.home.application.ingest.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.home.domain.ingest.matching.TradeMatchStatus;
import com.home.domain.ingest.matching.TradeMatchPath;
import com.home.domain.trade.RtmsJibunPnu;

/**
 * RTMS row에서 얻은 PNU/aptSeq/name evidence와 internal complex 후보를 비교하는 application policy입니다.
 */
public class ComplexMatchCandidatePolicy {

	private static final int CANDIDATE_ID_LIMIT = 20;

	public ComplexMatchResult match(
		String aptSeq,
		String aptName,
		RtmsJibunPnu jibunPnu,
		List<ComplexMatchCandidate> aptSeqCandidates,
		List<ComplexMatchCandidate> pnuCandidates
	) {
		Objects.requireNonNull(jibunPnu, "jibunPnu is required");
		List<ComplexMatchCandidate> aptSeqMatches = List.copyOf(Objects.requireNonNullElse(aptSeqCandidates, List.of()));
		List<ComplexMatchCandidate> pnuMatches = List.copyOf(Objects.requireNonNullElse(pnuCandidates, List.of()));
		String normalizedAptSeq = trimToNull(aptSeq);

		if (normalizedAptSeq != null) {
			if (aptSeqMatches.size() == 1) {
				return matchUniqueAptSeq(aptName, normalizedAptSeq, aptSeqMatches.get(0), jibunPnu, pnuMatches);
			}
			if (aptSeqMatches.size() > 1) {
				return ComplexMatchResult.failed(
					TradeMatchStatus.AMBIGUOUS,
					"ambiguous aptSeq=" + normalizedAptSeq,
					jibunPnu,
					aptSeqMatches.size(),
					candidateIds(aptSeqMatches)
				);
			}
		}

		if (!jibunPnu.available()) {
			String reason = "pnu unavailable: " + jibunPnu.pnuUnavailableReason();
			if (normalizedAptSeq != null) {
				reason += " aptSeq=" + normalizedAptSeq;
			}
			return ComplexMatchResult.failed(TradeMatchStatus.PNU_UNAVAILABLE, reason, jibunPnu, 0, List.of());
		}

		if (pnuMatches.isEmpty()) {
			String reason = "no complex matched ";
			if (normalizedAptSeq != null) {
				reason += "aptSeq=" + normalizedAptSeq + ", ";
			}
			reason += "pnu=" + jibunPnu.derivedPnu();
			return ComplexMatchResult.failed(TradeMatchStatus.UNMATCHED, reason, jibunPnu, 0, List.of());
		}
		if (pnuMatches.size() == 1) {
			ComplexMatchCandidate candidate = pnuMatches.get(0);
			if (!nameMatches(aptName, candidate)) {
				return ComplexMatchResult.failed(
					TradeMatchStatus.NAME_CONFLICT,
					"name conflict pnu=" + jibunPnu.derivedPnu() + " aptName=" + valueOrUnknown(aptName),
					jibunPnu,
					1,
					candidateIds(pnuMatches)
				);
			}
			return matched(candidate, TradeMatchPath.PNU_UNIQUE, TradeMatchStatus.MATCHED, jibunPnu, 1, candidateIds(pnuMatches),
				null);
		}

		CandidateNameMatch chosen = chooseByName(aptName, pnuMatches);
		if (chosen != null) {
			return matched(
				chosen.candidate(),
				chosen.matchPath(),
				TradeMatchStatus.MATCHED,
				jibunPnu,
				pnuMatches.size(),
				candidateIds(pnuMatches),
				null
			);
		}
		return ComplexMatchResult.failed(
			TradeMatchStatus.AMBIGUOUS,
			"ambiguous pnu=" + jibunPnu.derivedPnu() + " aptName=" + valueOrUnknown(aptName),
			jibunPnu,
			pnuMatches.size(),
			candidateIds(pnuMatches)
		);
	}

	private ComplexMatchResult matchUniqueAptSeq(
		String aptName,
		String aptSeq,
		ComplexMatchCandidate candidate,
		RtmsJibunPnu jibunPnu,
		List<ComplexMatchCandidate> pnuCandidates
	) {
		if (!jibunPnu.available()) {
			return ComplexMatchResult.failed(
				TradeMatchStatus.PNU_UNAVAILABLE,
				"pnu unavailable: " + jibunPnu.pnuUnavailableReason() + " aptSeq=" + aptSeq,
				jibunPnu,
				1,
				List.of(candidate.complexId())
			);
		}
		if (!jibunPnu.derivedPnu().equals(candidate.parcelPnu())) {
			List<Long> candidateIds = conflictCandidateIds(candidate, pnuCandidates);
			return ComplexMatchResult.failed(
				TradeMatchStatus.PNU_CONFLICT,
				"APTSEQ parcel pnu conflict aptSeq=%s derivedPnu=%s complexPnu=%s".formatted(
					aptSeq,
					jibunPnu.derivedPnu(),
					candidate.parcelPnu()
				),
				jibunPnu,
				conflictCandidateCount(candidate, pnuCandidates),
				candidateIds
			);
		}
		TradeMatchStatus status = nameVariant(aptName, candidate)
			? TradeMatchStatus.MATCHED_NAME_VARIANT
			: TradeMatchStatus.MATCHED;
		String failureReason = status.isMatchedNameVariant()
			? "observed RTMS name differs from master name"
			: null;
		return matched(candidate, TradeMatchPath.APTSEQ, status, jibunPnu, 1, List.of(candidate.complexId()), failureReason);
	}

	private CandidateNameMatch chooseByName(String aptName, List<ComplexMatchCandidate> candidates) {
		String target = normalizeName(aptName);
		if (target.isBlank()) {
			return null;
		}

		int bestScore = 0;
		CandidateNameMatch best = null;
		boolean tie = false;
		for (ComplexMatchCandidate candidate : candidates) {
			NameScore score = scoreName(target, candidate);
			if (score.value() == 0) {
				continue;
			}
			if (score.value() > bestScore) {
				bestScore = score.value();
				best = new CandidateNameMatch(candidate, score.matchPath());
				tie = false;
			}
			else if (score.value() == bestScore) {
				tie = true;
			}
		}
		return bestScore > 0 && !tie ? best : null;
	}

	private boolean nameMatches(String aptName, ComplexMatchCandidate candidate) {
		String target = normalizeName(aptName);
		return !target.isBlank() && scoreName(target, candidate).value() > 0;
	}

	private boolean nameVariant(String aptName, ComplexMatchCandidate candidate) {
		String target = normalizeName(aptName);
		return !target.isBlank() && scoreName(target, candidate).value() == 0;
	}

	private NameScore scoreName(String target, ComplexMatchCandidate candidate) {
		String tradeName = normalizeName(candidate.tradeName());
		String name = normalizeName(candidate.name());

		if (target.equals(tradeName) || target.equals(name)) {
			return new NameScore(4, TradeMatchPath.PNU_NAME);
		}
		if (candidate.normalizedAliases().contains(target)) {
			return new NameScore(3, TradeMatchPath.PNU_ALIAS_NAME);
		}
		if (!tradeName.isBlank() && (tradeName.contains(target) || target.contains(tradeName))) {
			return new NameScore(2, TradeMatchPath.PNU_NAME);
		}
		if (!name.isBlank() && (name.contains(target) || target.contains(name))) {
			return new NameScore(2, TradeMatchPath.PNU_NAME);
		}
		for (String alias : candidate.normalizedAliases()) {
			if (!alias.isBlank() && (alias.contains(target) || target.contains(alias))) {
				return new NameScore(1, TradeMatchPath.PNU_ALIAS_NAME);
			}
		}
		return new NameScore(0, TradeMatchPath.PNU_NAME);
	}

	private ComplexMatchResult matched(
		ComplexMatchCandidate candidate,
		TradeMatchPath path,
		TradeMatchStatus status,
		RtmsJibunPnu jibunPnu,
		int candidateCount,
		List<Long> candidateIds,
		String failureReason
	) {
		return ComplexMatchResult.matched(
			candidate.complexId(),
			candidate.complexPk(),
			path.storedValue(),
			status,
			jibunPnu,
			candidateCount,
			candidateIds,
			failureReason
		);
	}

	private List<Long> candidateIds(List<ComplexMatchCandidate> candidates) {
		List<Long> ids = new ArrayList<>();
		for (ComplexMatchCandidate candidate : candidates) {
			if (ids.size() >= CANDIDATE_ID_LIMIT) {
				break;
			}
			ids.add(candidate.complexId());
		}
		return ids;
	}

	private int conflictCandidateCount(ComplexMatchCandidate aptSeqCandidate, List<ComplexMatchCandidate> pnuCandidates) {
		return allConflictCandidateIds(aptSeqCandidate, pnuCandidates).size();
	}

	private List<Long> conflictCandidateIds(ComplexMatchCandidate aptSeqCandidate, List<ComplexMatchCandidate> pnuCandidates) {
		List<Long> ids = allConflictCandidateIds(aptSeqCandidate, pnuCandidates);
		if (ids.size() <= CANDIDATE_ID_LIMIT) {
			return ids;
		}
		return List.copyOf(ids.subList(0, CANDIDATE_ID_LIMIT));
	}

	private List<Long> allConflictCandidateIds(
		ComplexMatchCandidate aptSeqCandidate,
		List<ComplexMatchCandidate> pnuCandidates
	) {
		List<Long> ids = new ArrayList<>();
		addCandidateId(ids, aptSeqCandidate.complexId());
		for (ComplexMatchCandidate candidate : pnuCandidates) {
			addCandidateId(ids, candidate.complexId());
		}
		return ids;
	}

	private void addCandidateId(List<Long> ids, Long complexId) {
		if (!ids.contains(complexId)) {
			ids.add(complexId);
		}
	}

	private String normalizeName(String value) {
		String text = trimToNull(value);
		if (text == null) {
			return "";
		}
		return text.replaceAll("\\s+", "")
			.replaceAll("[()\\[\\]{}.,·\\-_/]", "")
			.toLowerCase(Locale.ROOT);
	}

	private String valueOrUnknown(String value) {
		String text = trimToNull(value);
		return text == null ? "unknown" : text;
	}

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}

	private record CandidateNameMatch(
		ComplexMatchCandidate candidate,
		TradeMatchPath matchPath
	) {
	}

	private record NameScore(
		int value,
		TradeMatchPath matchPath
	) {
	}
}
