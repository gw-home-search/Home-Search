package com.home.infrastructure.persistence.ingest;

import java.util.List;
import java.util.Objects;

import com.home.application.ingest.matching.ComplexMatchCandidate;
import com.home.application.ingest.matching.ComplexMatchCandidatePolicy;
import com.home.application.ingest.matching.ComplexMatchResult;
import com.home.application.ingest.matching.ComplexMatcher;
import com.home.application.ingest.trade.OpenApiTradeItem;
import com.home.domain.trade.RtmsJibunPnu;
import com.home.application.ingest.normalization.RtmsJibunPnuNormalizer;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * RTMS trade item을 local PostGIS-backed canonical complex rows에 연결하는 matcher입니다.
 */
public class JdbcComplexMatcher implements ComplexMatcher {

	private final JdbcClient jdbcClient;
	private final ComplexMatchCandidatePolicy policy;

	public JdbcComplexMatcher(JdbcClient jdbcClient) {
		this(jdbcClient, new ComplexMatchCandidatePolicy());
	}

	JdbcComplexMatcher(JdbcClient jdbcClient, ComplexMatchCandidatePolicy policy) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
		this.policy = Objects.requireNonNull(policy);
	}

	@Override
	public ComplexMatchResult match(OpenApiTradeItem item) {
		Objects.requireNonNull(item, "item is required");

		RtmsJibunPnu jibunPnu = RtmsJibunPnuNormalizer.normalize(item);
		String aptSeq = trimToNull(item.aptSeq());
		List<ComplexMatchCandidate> aptSeqCandidates = List.of();
		if (aptSeq != null) {
			aptSeqCandidates = findByAptSeq(aptSeq);
		}
		List<ComplexMatchCandidate> pnuCandidates = jibunPnu.available() ? findByPnu(jibunPnu.derivedPnu()) : List.of();
		return policy.match(aptSeq, item.aptName(), jibunPnu, aptSeqCandidates, pnuCandidates);
	}

	private List<ComplexMatchCandidate> findByAptSeq(String aptSeq) {
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
			.query((resultSet, rowNumber) -> new ComplexMatchCandidate(
				resultSet.getLong("complex_id"),
				resultSet.getString("complex_pk"),
				resultSet.getString("trade_name"),
				resultSet.getString("name"),
				resultSet.getString("pnu"),
				List.of()
			))
			.list();
	}

	private List<ComplexMatchCandidate> findByPnu(String pnu) {
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
			.query((resultSet, rowNumber) -> new ComplexMatchCandidate(
				resultSet.getLong("complex_id"),
				resultSet.getString("complex_pk"),
				resultSet.getString("trade_name"),
				resultSet.getString("name"),
				resultSet.getString("pnu"),
				parseAliases(resultSet.getString("normalized_aliases"))
			))
			.list();
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

	private String trimToNull(String value) {
		return value != null && !value.isBlank() ? value.trim() : null;
	}
}
