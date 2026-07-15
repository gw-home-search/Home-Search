package com.home.infrastructure.persistence.search;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.SearchComplexResult;
import com.home.application.search.ComplexSearchReader;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcComplexSearchReader implements ComplexSearchReader {

	private static final String COMPLEX_SEARCH_SQL = """
		WITH query_tokens AS (
		    SELECT
		        token_no,
		        lower(token) AS raw_token,
		        hs_normalize_complex_search_name(token) AS normalized_token,
		        '%' || hs_escape_like_pattern(lower(token)) || '%' AS raw_pattern,
		        '%' || hs_escape_like_pattern(hs_normalize_complex_search_name(token)) || '%' AS normalized_pattern
		    FROM regexp_split_to_table(btrim(CAST(:query AS text)), '[[:space:]]+')
		         WITH ORDINALITY AS split(token, token_no)
		),
		query_meta AS (
		    SELECT count(*) AS token_count
		    FROM query_tokens
		),
		name_hits AS (
		    SELECT c.id AS complex_id, token.token_no, 'NAME' AS source
		    FROM complex c
		    CROSS JOIN query_tokens token
		    WHERE lower(c.display_name) LIKE token.raw_pattern ESCAPE chr(92)
		       OR lower(c.name) LIKE token.raw_pattern ESCAPE chr(92)
		       OR lower(COALESCE(c.trade_name, '')) LIKE token.raw_pattern ESCAPE chr(92)
		       OR (
		           token.normalized_token <> ''
		           AND c.search_name LIKE token.normalized_pattern ESCAPE chr(92)
		       )
		),
		alias_hits AS (
		    SELECT alias.complex_id, token.token_no, 'ALIAS' AS source
		    FROM complex_name_alias alias
		    CROSS JOIN query_tokens token
		    WHERE (
		              char_length(token.raw_token) >= 3
		              AND lower(alias.alias_name) LIKE token.raw_pattern ESCAPE chr(92)
		          )
		       OR (
		           token.normalized_token <> ''
		           AND (
		               alias.normalized_name = token.normalized_token
		               OR (
		                   char_length(token.normalized_token) >= 3
		                   AND alias.normalized_name LIKE token.normalized_pattern ESCAPE chr(92)
		               )
		           )
		       )
		),
		address_hits AS (
		    SELECT c.id AS complex_id, token.token_no, 'ADDRESS' AS source
		    FROM parcel p
		    JOIN complex c ON c.parcel_id = p.id
		    CROSS JOIN query_tokens token
		    WHERE lower(COALESCE(p.address, '')) LIKE token.raw_pattern ESCAPE chr(92)
		),
		token_hits AS (
		    SELECT * FROM name_hits
		    UNION ALL
		    SELECT * FROM alias_hits
		    UNION ALL
		    SELECT * FROM address_hits
		),
		candidate_stats AS (
		    SELECT
		        hit.complex_id,
		        count(DISTINCT hit.token_no) FILTER (WHERE hit.source = 'NAME') AS name_token_count,
		        count(DISTINCT hit.token_no) FILTER (WHERE hit.source = 'ALIAS') AS alias_token_count,
		        count(DISTINCT hit.token_no) FILTER (WHERE hit.source = 'ADDRESS') AS address_token_count
		    FROM token_hits hit
		    GROUP BY hit.complex_id
		    HAVING count(DISTINCT hit.token_no) = (SELECT token_count FROM query_meta)
		)
		SELECT
		    c.id AS complex_id,
		    COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name) AS complex_name,
		    p.id AS parcel_id,
		    COALESCE(display_coordinate.latitude, p.latitude) AS latitude,
		    COALESCE(display_coordinate.longitude, p.longitude) AS longitude,
		    p.address
		FROM candidate_stats candidate
		JOIN complex c ON c.id = candidate.complex_id
		JOIN parcel p ON p.id = c.parcel_id
		LEFT JOIN complex_display_coordinate display_coordinate ON display_coordinate.complex_id = c.id
		CROSS JOIN query_meta
		ORDER BY
		    CASE
		        WHEN query_meta.token_count > 1
		             AND (lower(c.display_name) = :lowerQuery OR c.search_name = :normalizedQuery) THEN 0
		        WHEN query_meta.token_count > 1
		             AND (
		                 lower(c.display_name) LIKE :prefixPattern ESCAPE chr(92)
		                 OR c.search_name LIKE :normalizedPrefixPattern ESCAPE chr(92)
		             ) THEN 1
		        WHEN query_meta.token_count > 1 THEN 2
		        WHEN lower(c.display_name) = :lowerQuery
		            OR c.search_name = :normalizedQuery
		            OR lower(c.name) = :lowerQuery
		            OR lower(COALESCE(c.trade_name, '')) = :lowerQuery THEN 0
		        WHEN lower(c.display_name) LIKE :prefixPattern ESCAPE chr(92)
		            OR c.search_name LIKE :normalizedPrefixPattern ESCAPE chr(92)
		            OR lower(c.name) LIKE :prefixPattern ESCAPE chr(92)
		            OR lower(COALESCE(c.trade_name, '')) LIKE :prefixPattern ESCAPE chr(92) THEN 1
		        WHEN EXISTS (
		            SELECT 1
		            FROM complex_name_alias a
		            WHERE a.complex_id = c.id
		              AND (
		                  lower(a.alias_name) = :lowerQuery
		                  OR lower(a.alias_name) LIKE :prefixPattern ESCAPE chr(92)
		                  OR a.normalized_name = :normalizedQuery
		                  OR a.normalized_name LIKE :normalizedPrefixPattern ESCAPE chr(92)
		              )
		        ) THEN 2
		        WHEN lower(c.display_name) LIKE :pattern ESCAPE chr(92)
		            OR c.search_name LIKE :normalizedPattern ESCAPE chr(92)
		            OR lower(c.name) LIKE :pattern ESCAPE chr(92)
		            OR lower(COALESCE(c.trade_name, '')) LIKE :pattern ESCAPE chr(92) THEN 3
		        WHEN EXISTS (
		            SELECT 1
		            FROM complex_name_alias a
		            WHERE a.complex_id = c.id
		              AND (
		                  lower(a.alias_name) LIKE :pattern ESCAPE chr(92)
		                  OR a.normalized_name LIKE :normalizedPattern ESCAPE chr(92)
		              )
		        ) THEN 4
		        WHEN lower(COALESCE(p.address, '')) LIKE :prefixPattern ESCAPE chr(92) THEN 5
		        WHEN lower(COALESCE(p.address, '')) LIKE :pattern ESCAPE chr(92) THEN 6
		        ELSE 7
		    END,
		    CASE WHEN query_meta.token_count > 1 THEN candidate.name_token_count ELSE 0 END DESC,
		    CASE WHEN query_meta.token_count > 1 THEN candidate.alias_token_count ELSE 0 END DESC,
		    CASE WHEN query_meta.token_count > 1 THEN candidate.address_token_count ELSE 0 END DESC,
		    COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name),
		    c.id
		LIMIT :limit
		""";

	private final JdbcClient jdbcClient;

	public JdbcComplexSearchReader(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	@Override
	public List<SearchComplexResult> searchComplexes(String query) {
		return searchStatement(PropertySearchTerms.from(query), 20)
			.query(this::mapSearchComplex)
			.list();
	}

	@Override
	public List<ComplexSuggestionResult> suggestComplexes(String query, int limit) {
		return searchStatement(PropertySearchTerms.from(query), limit)
			.query(this::mapComplexSuggestion)
			.list();
	}

	private JdbcClient.StatementSpec searchStatement(PropertySearchTerms terms, int limit) {
		return jdbcClient.sql(COMPLEX_SEARCH_SQL)
			.param("query", terms.query())
			.param("lowerQuery", terms.lowerQuery())
			.param("pattern", terms.pattern())
			.param("prefixPattern", terms.prefixPattern())
			.param("normalizedQuery", terms.normalizedQuery())
			.param("normalizedPattern", terms.normalizedPattern())
			.param("normalizedPrefixPattern", terms.normalizedPrefixPattern())
			.param("limit", limit);
	}

	private SearchComplexResult mapSearchComplex(ResultSet resultSet, int rowNumber) throws SQLException {
		return new SearchComplexResult(
			resultSet.getLong("complex_id"),
			resultSet.getString("complex_name"),
			resultSet.getLong("parcel_id"),
			doubleOrNull(resultSet, "latitude"),
			doubleOrNull(resultSet, "longitude"),
			resultSet.getString("address")
		);
	}

	private ComplexSuggestionResult mapComplexSuggestion(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ComplexSuggestionResult(
			resultSet.getLong("complex_id"),
			resultSet.getString("complex_name"),
			resultSet.getLong("parcel_id"),
			resultSet.getString("address")
		);
	}

	private Double doubleOrNull(ResultSet resultSet, String column) throws SQLException {
		BigDecimal value = resultSet.getBigDecimal(column);
		return value == null ? null : value.doubleValue();
	}
}
