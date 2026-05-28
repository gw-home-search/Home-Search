package com.home.infrastructure.persistence.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.home.application.ingest.ComplexMatchResult;
import com.home.application.ingest.ComplexMatcher;
import com.home.application.ingest.OpenApiTradeItem;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RTMS trade item을 local PostGIS-backed canonical complex rows에 연결하는 matcher입니다.
 */
public class JdbcComplexMatcher implements ComplexMatcher {

	private final JdbcClient jdbcClient;

	public JdbcComplexMatcher(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public ComplexMatchResult match(OpenApiTradeItem item) {
		Objects.requireNonNull(item, "item is required");

		List<String> failures = new ArrayList<>();
		String aptSeq = trimToNull(item.aptSeq());
		if (aptSeq != null) {
			List<ComplexCandidate> candidates = findByAptSeq(aptSeq);
			if (candidates.size() == 1) {
				return candidates.get(0).matched("APTSEQ");
			}
			if (candidates.size() > 1) {
				failures.add("ambiguous aptSeq=" + aptSeq);
			}
			else {
				failures.add("aptSeq=" + aptSeq);
			}
		}

		String pnu = RtmsPnuBuilder.build(item).orElse(null);
		if (pnu != null) {
			List<ComplexCandidate> candidates = findByPnu(pnu);
			if (candidates.size() == 1) {
				return candidates.get(0).matched("PNU_UNIQUE");
			}
			if (candidates.size() > 1) {
				CandidateNameMatch chosen = chooseByName(item.aptName(), candidates);
				if (chosen != null) {
					return chosen.candidate().matched(chosen.matchPath());
				}
				failures.add("ambiguous pnu=" + pnu + " aptName=" + valueOrUnknown(item.aptName()));
			}
			else {
				failures.add("pnu=" + pnu);
			}
		}
		else {
			failures.add("pnu unavailable");
		}

		return ComplexMatchResult.failed("no complex matched " + String.join(", ", failures));
	}

	private List<ComplexCandidate> findByAptSeq(String aptSeq) {
		return jdbcClient.sql("""
			SELECT id AS complex_id,
			       complex_pk,
			       trade_name,
			       name
			FROM complex
			WHERE apt_seq = :aptSeq
			ORDER BY id
			LIMIT 2
			""")
			.param("aptSeq", aptSeq)
			.query((resultSet, rowNumber) -> new ComplexCandidate(
				resultSet.getLong("complex_id"),
				resultSet.getString("complex_pk"),
				resultSet.getString("trade_name"),
				resultSet.getString("name"),
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
			       COALESCE(string_agg(a.normalized_name, '|' ORDER BY a.id), '') AS normalized_aliases
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			LEFT JOIN complex_name_alias a ON a.complex_id = c.id
			WHERE p.pnu = :pnu
			GROUP BY c.id, c.complex_pk, c.trade_name, c.name
			ORDER BY c.id
			""")
			.param("pnu", pnu)
			.query((resultSet, rowNumber) -> new ComplexCandidate(
				resultSet.getLong("complex_id"),
				resultSet.getString("complex_pk"),
				resultSet.getString("trade_name"),
				resultSet.getString("name"),
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
		List<String> normalizedAliases
	) {

		private ComplexMatchResult matched(String path) {
			return ComplexMatchResult.matched(complexId, complexPk, path);
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
