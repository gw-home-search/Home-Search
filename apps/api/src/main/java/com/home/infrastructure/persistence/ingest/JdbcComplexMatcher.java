package com.home.infrastructure.persistence.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.home.application.ingest.ComplexMatchResult;
import com.home.application.ingest.ComplexMatcher;
import com.home.application.ingest.OpenApiTradeItem;
import com.home.application.ingest.RtmsJibunPnu;
import com.home.application.ingest.RtmsJibunPnuNormalizer;
import com.home.application.ingest.TradeMatchStatus;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RTMS trade item을 local PostGIS-backed canonical complex rows에 연결하는 matcher입니다.
 */
public class JdbcComplexMatcher implements ComplexMatcher {

	private static final int CANDIDATE_ID_LIMIT = 20;

	private final JdbcClient jdbcClient;

	public JdbcComplexMatcher(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public ComplexMatchResult match(OpenApiTradeItem item) {
		Objects.requireNonNull(item, "item is required");

		RtmsJibunPnu jibunPnu = RtmsJibunPnuNormalizer.normalize(item);
		String aptSeq = trimToNull(item.aptSeq());
		if (aptSeq != null) {
			List<ComplexCandidate> aptSeqCandidates = findByAptSeq(aptSeq);
			if (aptSeqCandidates.size() == 1) {
				return matchUniqueAptSeq(item, aptSeq, aptSeqCandidates.get(0), jibunPnu);
			}
			if (aptSeqCandidates.size() > 1) {
				return ComplexMatchResult.failed(
					TradeMatchStatus.AMBIGUOUS,
					"ambiguous aptSeq=" + aptSeq,
					jibunPnu,
					aptSeqCandidates.size(),
					candidateIds(aptSeqCandidates)
				);
			}
		}

		if (!jibunPnu.available()) {
			String reason = "pnu unavailable: " + jibunPnu.pnuUnavailableReason();
			if (aptSeq != null) {
				reason += " aptSeq=" + aptSeq;
			}
			return ComplexMatchResult.failed(TradeMatchStatus.PNU_UNAVAILABLE, reason, jibunPnu, 0, List.of());
		}

		List<ComplexCandidate> pnuCandidates = findByPnu(jibunPnu.derivedPnu());
		if (pnuCandidates.isEmpty()) {
			String reason = "no complex matched ";
			if (aptSeq != null) {
				reason += "aptSeq=" + aptSeq + ", ";
			}
			reason += "pnu=" + jibunPnu.derivedPnu();
			return ComplexMatchResult.failed(TradeMatchStatus.UNMATCHED, reason, jibunPnu, 0, List.of());
		}
		if (pnuCandidates.size() == 1) {
			ComplexCandidate candidate = pnuCandidates.get(0);
			if (!nameMatches(item.aptName(), candidate)) {
				return ComplexMatchResult.failed(
					TradeMatchStatus.NAME_CONFLICT,
					"name conflict pnu=" + jibunPnu.derivedPnu() + " aptName=" + valueOrUnknown(item.aptName()),
					jibunPnu,
					1,
					candidateIds(pnuCandidates)
				);
			}
			return candidate.matched(
				"PNU_UNIQUE",
				TradeMatchStatus.MATCHED,
				jibunPnu,
				1,
				candidateIds(pnuCandidates),
				null
			);
		}

		CandidateNameMatch chosen = chooseByName(item.aptName(), pnuCandidates);
		if (chosen != null) {
			return chosen.candidate().matched(
				chosen.matchPath(),
				TradeMatchStatus.MATCHED,
				jibunPnu,
				pnuCandidates.size(),
				candidateIds(pnuCandidates),
				null
			);
		}
		return ComplexMatchResult.failed(
			TradeMatchStatus.AMBIGUOUS,
			"ambiguous pnu=" + jibunPnu.derivedPnu() + " aptName=" + valueOrUnknown(item.aptName()),
			jibunPnu,
			pnuCandidates.size(),
			candidateIds(pnuCandidates)
		);
	}

	private ComplexMatchResult matchUniqueAptSeq(
		OpenApiTradeItem item,
		String aptSeq,
		ComplexCandidate candidate,
		RtmsJibunPnu jibunPnu
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
			List<ComplexCandidate> pnuCandidates = findByPnu(jibunPnu.derivedPnu());
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
		TradeMatchStatus status = nameVariant(item.aptName(), candidate)
			? TradeMatchStatus.MATCHED_NAME_VARIANT
			: TradeMatchStatus.MATCHED;
		String failureReason = status == TradeMatchStatus.MATCHED_NAME_VARIANT
			? "observed RTMS name differs from master name"
			: null;
		return candidate.matched("APTSEQ", status, jibunPnu, 1, List.of(candidate.complexId()), failureReason);
	}

	private List<ComplexCandidate> findByAptSeq(String aptSeq) {
		return jdbcClient.sql("""
			SELECT c.id AS complex_id,
			       c.complex_pk,
			       c.trade_name,
			       c.name,
			       p.pnu
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			WHERE c.apt_seq = :aptSeq
			ORDER BY c.id
			LIMIT 2
			""")
			.param("aptSeq", aptSeq)
			.query((resultSet, rowNumber) -> new ComplexCandidate(
				resultSet.getLong("complex_id"),
				resultSet.getString("complex_pk"),
				resultSet.getString("trade_name"),
				resultSet.getString("name"),
				resultSet.getString("pnu"),
				List.of()
			))
			.list();
	}

	private List<ComplexCandidate> findByPnu(String pnu) {
		return jdbcClient.sql("""
			SELECT c.id AS complex_id,
			       c.complex_pk,
			       c.trade_name,
			       c.name,
			       p.pnu,
			       COALESCE(string_agg(a.normalized_name, '|' ORDER BY a.id), '') AS normalized_aliases
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			LEFT JOIN complex_name_alias a ON a.complex_id = c.id
			WHERE p.pnu = :pnu
			GROUP BY c.id, c.complex_pk, c.trade_name, c.name, p.pnu
			ORDER BY c.id
			""")
			.param("pnu", pnu)
			.query((resultSet, rowNumber) -> new ComplexCandidate(
				resultSet.getLong("complex_id"),
				resultSet.getString("complex_pk"),
				resultSet.getString("trade_name"),
				resultSet.getString("name"),
				resultSet.getString("pnu"),
				parseAliases(resultSet.getString("normalized_aliases"))
			))
			.list();
	}

	private CandidateNameMatch chooseByName(String aptName, List<ComplexCandidate> candidates) {
		String target = normalizeName(aptName);
		if (target.isBlank()) {
			return null;
		}

		int bestScore = 0;
		CandidateNameMatch best = null;
		boolean tie = false;
		for (ComplexCandidate candidate : candidates) {
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

	private boolean nameMatches(String aptName, ComplexCandidate candidate) {
		String target = normalizeName(aptName);
		return !target.isBlank() && scoreName(target, candidate).value() > 0;
	}

	private boolean nameVariant(String aptName, ComplexCandidate candidate) {
		String target = normalizeName(aptName);
		return !target.isBlank() && scoreName(target, candidate).value() == 0;
	}

	private NameScore scoreName(String target, ComplexCandidate candidate) {
		String tradeName = normalizeName(candidate.tradeName());
		String name = normalizeName(candidate.name());

		if (target.equals(tradeName) || target.equals(name)) {
			return new NameScore(4, "PNU_NAME");
		}
		if (candidate.normalizedAliases().contains(target)) {
			return new NameScore(3, "PNU_ALIAS_NAME");
		}
		if (!tradeName.isBlank() && (tradeName.contains(target) || target.contains(tradeName))) {
			return new NameScore(2, "PNU_NAME");
		}
		if (!name.isBlank() && (name.contains(target) || target.contains(name))) {
			return new NameScore(2, "PNU_NAME");
		}
		for (String alias : candidate.normalizedAliases()) {
			if (!alias.isBlank() && (alias.contains(target) || target.contains(alias))) {
				return new NameScore(1, "PNU_ALIAS_NAME");
			}
		}
		return new NameScore(0, "PNU_NAME");
	}

	private List<String> parseAliases(String normalizedAliases) {
		String text = trimToNull(normalizedAliases);
		if (text == null) {
			return List.of();
		}
		return List.of(text.split("\\|"))
			.stream()
			.filter(alias -> !alias.isBlank())
			.toList();
	}

	private List<Long> candidateIds(List<ComplexCandidate> candidates) {
		List<Long> ids = new ArrayList<>();
		for (ComplexCandidate candidate : candidates) {
			if (ids.size() >= CANDIDATE_ID_LIMIT) {
				break;
			}
			ids.add(candidate.complexId());
		}
		return ids;
	}

	private int conflictCandidateCount(ComplexCandidate aptSeqCandidate, List<ComplexCandidate> pnuCandidates) {
		return allConflictCandidateIds(aptSeqCandidate, pnuCandidates).size();
	}

	private List<Long> conflictCandidateIds(ComplexCandidate aptSeqCandidate, List<ComplexCandidate> pnuCandidates) {
		List<Long> ids = allConflictCandidateIds(aptSeqCandidate, pnuCandidates);
		if (ids.size() <= CANDIDATE_ID_LIMIT) {
			return ids;
		}
		return List.copyOf(ids.subList(0, CANDIDATE_ID_LIMIT));
	}

	private List<Long> allConflictCandidateIds(ComplexCandidate aptSeqCandidate, List<ComplexCandidate> pnuCandidates) {
		List<Long> ids = new ArrayList<>();
		addCandidateId(ids, aptSeqCandidate.complexId());
		for (ComplexCandidate candidate : pnuCandidates) {
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

	private record ComplexCandidate(
		Long complexId,
		String complexPk,
		String tradeName,
		String name,
		String parcelPnu,
		List<String> normalizedAliases
	) {

		private ComplexMatchResult matched(
			String path,
			TradeMatchStatus status,
			RtmsJibunPnu jibunPnu,
			int candidateCount,
			List<Long> candidateIds,
			String failureReason
		) {
			return ComplexMatchResult.matched(
				complexId,
				complexPk,
				path,
				status,
				jibunPnu,
				candidateCount,
				candidateIds,
				failureReason
			);
		}
	}

	private record CandidateNameMatch(
		ComplexCandidate candidate,
		String matchPath
	) {
	}

	private record NameScore(
		int value,
		String matchPath
	) {
	}
}
