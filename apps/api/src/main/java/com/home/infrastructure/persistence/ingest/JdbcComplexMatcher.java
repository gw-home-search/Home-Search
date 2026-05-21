package com.home.infrastructure.persistence.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.home.application.ingest.ComplexMatchResult;
import com.home.application.ingest.ComplexMatcher;
import com.home.application.ingest.OpenApiTradeItem;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RTMS trade item을 local PostGIS-backed V1 complex rows에 연결하는 matcher입니다.
 */
public class JdbcComplexMatcher implements ComplexMatcher {

	private static final Pattern JIBUN_PATTERN = Pattern.compile("(\\d+)(?:\\D+(\\d+))?");

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

		String pnu = buildPnu(item);
		if (pnu != null) {
			List<ComplexCandidate> candidates = findByPnu(pnu);
			if (candidates.size() == 1) {
				return candidates.get(0).matched("PNU_UNIQUE");
			}
			if (candidates.size() > 1) {
				ComplexCandidate chosen = chooseByName(item.aptName(), candidates);
				if (chosen != null) {
					return chosen.matched("PNU_NAME");
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
				resultSet.getString("name")
			))
			.list();
	}

	private List<ComplexCandidate> findByPnu(String pnu) {
		return jdbcClient.sql("""
			SELECT c.id AS complex_id,
			       c.complex_pk,
			       c.trade_name,
			       c.name
			FROM complex c
			JOIN parcel p ON p.id = c.parcel_id
			WHERE p.pnu = :pnu
			ORDER BY c.id
			""")
			.param("pnu", pnu)
			.query((resultSet, rowNumber) -> new ComplexCandidate(
				resultSet.getLong("complex_id"),
				resultSet.getString("complex_pk"),
				resultSet.getString("trade_name"),
				resultSet.getString("name")
			))
			.list();
	}

	private ComplexCandidate chooseByName(String aptName, List<ComplexCandidate> candidates) {
		String target = normalizeName(aptName);
		if (target.isBlank()) {
			return null;
		}

		int bestScore = 0;
		ComplexCandidate best = null;
		boolean tie = false;
		for (ComplexCandidate candidate : candidates) {
			int score = scoreName(target, candidate);
			if (score == 0) {
				continue;
			}
			if (score > bestScore) {
				bestScore = score;
				best = candidate;
				tie = false;
			}
			else if (score == bestScore) {
				tie = true;
			}
		}
		return bestScore > 0 && !tie ? best : null;
	}

	private int scoreName(String target, ComplexCandidate candidate) {
		String tradeName = normalizeName(candidate.tradeName());
		String name = normalizeName(candidate.name());

		if (target.equals(tradeName) || target.equals(name)) {
			return 3;
		}
		if (!tradeName.isBlank() && (tradeName.contains(target) || target.contains(tradeName))) {
			return 2;
		}
		if (!name.isBlank() && (name.contains(target) || target.contains(name))) {
			return 2;
		}
		return 0;
	}

	private String buildPnu(OpenApiTradeItem item) {
		String sggCd = trimToNull(item.sggCd());
		String umdCd = trimToNull(item.umdCd());
		if (sggCd == null || umdCd == null || sggCd.length() != 5 || umdCd.length() != 5) {
			return null;
		}

		JibunParts jibun = parseJibun(item.jibun());
		if (jibun == null) {
			return null;
		}

		String landCode = item.jibun() != null && item.jibun().contains("산") ? "2" : "1";
		return sggCd + umdCd + landCode + pad4(jibun.bon()) + pad4(jibun.bu());
	}

	private JibunParts parseJibun(String value) {
		String jibun = trimToNull(value);
		if (jibun == null) {
			return null;
		}
		Matcher matcher = JIBUN_PATTERN.matcher(jibun.replace("산", ""));
		if (!matcher.find()) {
			return null;
		}
		String bon = matcher.group(1);
		String bu = matcher.group(2) == null ? "0" : matcher.group(2);
		if (bon.length() > 4 || bu.length() > 4) {
			return null;
		}
		return new JibunParts(bon, bu);
	}

	private String pad4(String value) {
		return "0".repeat(4 - value.length()) + value;
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
		String name
	) {

		private ComplexMatchResult matched(String path) {
			return ComplexMatchResult.matched(complexId, complexPk, path);
		}
	}

	private record JibunParts(String bon, String bu) {
	}
}
