package com.home.infrastructure.persistence.search;

import com.home.application.read.ComplexSuggestionResult;
import com.home.application.read.SearchComplexResult;
import com.home.application.search.ComplexSearchReader;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcComplexSearchReader implements ComplexSearchReader {

    private static final Logger log = LoggerFactory.getLogger(JdbcComplexSearchReader.class);

    private static final String EXACT_CANDIDATES = """
		SELECT c.id AS complex_id FROM complex c WHERE lower(c.display_name) = :lowerQuery
		UNION
		SELECT c.id AS complex_id FROM complex c WHERE lower(c.name) = :lowerQuery
		UNION
		SELECT c.id AS complex_id FROM complex c WHERE lower(c.trade_name) = :lowerQuery
		UNION
		SELECT c.id AS complex_id
		FROM complex c
		WHERE :normalizedQuery <> '' AND c.search_name = :normalizedQuery
		UNION
		SELECT alias.complex_id
		FROM complex_name_alias alias
		WHERE lower(alias.alias_name) = :lowerQuery
		   OR (:normalizedQuery <> '' AND alias.normalized_name = :normalizedQuery)
		""";

    private static final String PREFIX_CANDIDATES = """
		WITH query_tokens AS (
		    SELECT
		        token_no,
		        lower(token) AS raw_token,
		        hs_normalize_complex_search_name(token) AS normalized_token,
		        hs_escape_like_pattern(lower(token)) || '%' AS raw_prefix,
		        hs_escape_like_pattern(hs_normalize_complex_search_name(token)) || '%' AS normalized_prefix,
		        (
		            SELECT lexeme
		            FROM unnest(tsvector_to_array(to_tsvector('simple', lower(token)))) AS lexeme
		            LIMIT 1
		        ) AS address_lexeme
		    FROM regexp_split_to_table(btrim(CAST(:query AS text)), '[[:space:]]+')
		         WITH ORDINALITY AS split(token, token_no)
		),
		query_meta AS (
		    SELECT count(*) AS token_count
		    FROM query_tokens
		),
		token_hits AS (
		    SELECT token.token_no, c.id AS complex_id
		    FROM query_tokens token
		    JOIN complex c ON lower(c.display_name) LIKE token.raw_prefix ESCAPE chr(92)
		    UNION ALL
		    SELECT token.token_no, c.id AS complex_id
		    FROM query_tokens token
		    JOIN complex c ON lower(c.name) LIKE token.raw_prefix ESCAPE chr(92)
		    UNION ALL
		    SELECT token.token_no, c.id AS complex_id
		    FROM query_tokens token
		    JOIN complex c ON lower(c.trade_name) LIKE token.raw_prefix ESCAPE chr(92)
		    UNION ALL
		    SELECT token.token_no, c.id AS complex_id
		    FROM query_tokens token
		    JOIN complex c ON token.normalized_token <> ''
		        AND c.search_name LIKE token.normalized_prefix ESCAPE chr(92)
		    UNION ALL
		    SELECT token.token_no, alias.complex_id
		    FROM query_tokens token
		    JOIN complex_name_alias alias
		      ON lower(alias.alias_name) LIKE token.raw_prefix ESCAPE chr(92)
		      OR (token.normalized_token <> ''
		          AND alias.normalized_name LIKE token.normalized_prefix ESCAPE chr(92))
		    UNION ALL
		    SELECT token.token_no, c.id AS complex_id
		    FROM query_tokens token
		    JOIN parcel address_parcel ON token.address_lexeme IS NOT NULL
		        AND to_tsvector('simple', lower(COALESCE(address_parcel.address, '')))
		            @@ to_tsquery('simple', quote_literal(token.address_lexeme) || ':*')
		    JOIN complex c ON c.parcel_id = address_parcel.id
		)
		SELECT hit.complex_id
		FROM token_hits hit
		GROUP BY hit.complex_id
		HAVING count(DISTINCT hit.token_no) = (SELECT token_count FROM query_meta)
		""";

    private static final String CONTAINS_CANDIDATES = """
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
		eligible_tokens AS (
		    SELECT token.*
		    FROM query_tokens token
		    WHERE char_length(token.raw_token) >= 3
		),
		token_hits AS (
		    SELECT token.token_no, c.id AS complex_id
		    FROM eligible_tokens token
		    JOIN complex c ON lower(c.display_name) LIKE token.raw_pattern ESCAPE chr(92)
		    UNION ALL
		    SELECT token.token_no, c.id AS complex_id
		    FROM eligible_tokens token
		    JOIN complex c ON lower(c.name) LIKE token.raw_pattern ESCAPE chr(92)
		    UNION ALL
		    SELECT token.token_no, c.id AS complex_id
		    FROM eligible_tokens token
		    JOIN complex c
		      ON lower(COALESCE(c.trade_name, '')) LIKE token.raw_pattern ESCAPE chr(92)
		    UNION ALL
		    SELECT token.token_no, c.id AS complex_id
		    FROM eligible_tokens token
		    JOIN complex c ON token.normalized_token <> ''
		        AND c.search_name LIKE token.normalized_pattern ESCAPE chr(92)
		    UNION ALL
		    SELECT token.token_no, alias.complex_id
		    FROM eligible_tokens token
		    JOIN complex_name_alias alias
		      ON lower(alias.alias_name) LIKE token.raw_pattern ESCAPE chr(92)
		      OR (token.normalized_token <> ''
		          AND alias.normalized_name LIKE token.normalized_pattern ESCAPE chr(92))
		    UNION ALL
		    SELECT token.token_no, c.id AS complex_id
		    FROM eligible_tokens token
		    JOIN parcel address_parcel
		      ON lower(COALESCE(address_parcel.address, '')) LIKE token.raw_pattern ESCAPE chr(92)
		    JOIN complex c ON c.parcel_id = address_parcel.id
		)
		SELECT hit.complex_id
		FROM token_hits hit
		GROUP BY hit.complex_id
		HAVING count(DISTINCT hit.token_no) = (SELECT token_count FROM query_meta)
		""";

    private static final String TWO_CHARACTER_CONTAINS_CANDIDATES = """
		WITH query_token AS (
		    SELECT
		        lower(CAST(:query AS text)) AS raw_token,
		        hs_normalize_complex_search_name(CAST(:query AS text)) AS normalized_token,
		        '%' || hs_escape_like_pattern(lower(CAST(:query AS text))) || '%' AS raw_pattern,
		        '%' || hs_escape_like_pattern(hs_normalize_complex_search_name(CAST(:query AS text))) || '%'
		            AS normalized_pattern
		),
		bounded_hits AS (
		    SELECT hit.id AS complex_id
		    FROM query_token token
		    CROSS JOIN LATERAL (
		        SELECT c.id
		        FROM complex c
		        WHERE lower(c.display_name) LIKE token.raw_pattern ESCAPE chr(92)
		        LIMIT 200
		    ) hit
		    UNION ALL
		    SELECT hit.id AS complex_id
		    FROM query_token token
		    CROSS JOIN LATERAL (
		        SELECT c.id
		        FROM complex c
		        WHERE lower(c.name) LIKE token.raw_pattern ESCAPE chr(92)
		        LIMIT 200
		    ) hit
		    UNION ALL
		    SELECT hit.id AS complex_id
		    FROM query_token token
		    CROSS JOIN LATERAL (
		        SELECT c.id
		        FROM complex c
		        WHERE lower(COALESCE(c.trade_name, '')) LIKE token.raw_pattern ESCAPE chr(92)
		        LIMIT 200
		    ) hit
		    UNION ALL
		    SELECT hit.id AS complex_id
		    FROM query_token token
		    CROSS JOIN LATERAL (
		        SELECT c.id
		        FROM complex c
		        WHERE token.normalized_token <> ''
		          AND c.search_name LIKE token.normalized_pattern ESCAPE chr(92)
		        LIMIT 200
		    ) hit
		    UNION ALL
		    SELECT hit.complex_id
		    FROM query_token token
		    CROSS JOIN LATERAL (
		        SELECT alias.complex_id
		        FROM complex_name_alias alias
		        WHERE lower(alias.alias_name) LIKE token.raw_pattern ESCAPE chr(92)
		           OR (token.normalized_token <> ''
		               AND alias.normalized_name LIKE token.normalized_pattern ESCAPE chr(92))
		        LIMIT 200
		    ) hit
		)
		SELECT DISTINCT hit.complex_id
		FROM bounded_hits hit
		""";

    private static final String SEARCH_PROJECTION = """
		SELECT
		    c.id AS complex_id,
		    COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name) AS complex_name,
		    p.id AS parcel_id,
		    COALESCE(display_coordinate.latitude, p.latitude) AS latitude,
		    COALESCE(display_coordinate.longitude, p.longitude) AS longitude,
		    p.address
		FROM (%s) candidate
		JOIN complex c ON c.id = candidate.complex_id
		JOIN parcel p ON p.id = c.parcel_id
		LEFT JOIN complex_display_coordinate display_coordinate ON display_coordinate.complex_id = c.id
		ORDER BY COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name), c.id
		LIMIT :limit
		""";

    private static final String SUGGESTION_PROJECTION = """
		SELECT
		    c.id AS complex_id,
		    COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name) AS complex_name,
		    p.id AS parcel_id,
		    p.address
		FROM (%s) candidate
		JOIN complex c ON c.id = candidate.complex_id
		JOIN parcel p ON p.id = c.parcel_id
		ORDER BY COALESCE(NULLIF(BTRIM(c.trade_name), ''), c.name), c.id
		LIMIT :limit
		""";

    private final JdbcClient jdbcClient;

    public JdbcComplexSearchReader(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public List<SearchComplexResult> searchComplexes(String query) {
        return firstResults(query, 20, false, this::mapSearchComplex);
    }

    @Override
    public List<ComplexSuggestionResult> suggestComplexes(String query, int limit) {
        return firstResults(query, limit, true, this::mapComplexSuggestion);
    }

    private <T> List<T> firstResults(String query, int limit, boolean suggestion, RowMapper<T> rowMapper) {
        PropertySearchTerms terms = PropertySearchTerms.from(query);
        for (SearchStage stage : SearchStage.values()) {
            long startedAt = System.nanoTime();
            List<T> results = stageStatement(stage, terms, limit, suggestion)
                    .query(rowMapper)
                    .list();
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            log.debug(
                    "Complex search stage completed stage={} elapsedMs={} resultCount={} suggestion={}",
                    stage,
                    elapsedMillis,
                    results.size(),
                    suggestion);
            if (!results.isEmpty()) {
                return results;
            }
        }
        return List.of();
    }

    private JdbcClient.StatementSpec stageStatement(
            SearchStage stage, PropertySearchTerms terms, int limit, boolean suggestion) {
        String candidates =
                switch (stage) {
                    case EXACT -> EXACT_CANDIDATES;
                    case PREFIX -> PREFIX_CANDIDATES;
                    case CONTAINS ->
                        terms.isSingleTwoCodePointQuery() ? TWO_CHARACTER_CONTAINS_CANDIDATES : CONTAINS_CANDIDATES;
                };
        String projection = suggestion ? SUGGESTION_PROJECTION : SEARCH_PROJECTION;
        JdbcClient.StatementSpec statement = jdbcClient.sql(projection.formatted(candidates));
        statement = switch (stage) {
            case EXACT ->
                statement.param("lowerQuery", terms.lowerQuery()).param("normalizedQuery", terms.normalizedQuery());
            case PREFIX, CONTAINS -> statement.param("query", terms.query());
        };
        return statement.param("limit", limit);
    }

    private SearchComplexResult mapSearchComplex(ResultSet resultSet, int rowNumber) throws SQLException {
        return new SearchComplexResult(
                resultSet.getLong("complex_id"),
                resultSet.getString("complex_name"),
                resultSet.getLong("parcel_id"),
                doubleOrNull(resultSet, "latitude"),
                doubleOrNull(resultSet, "longitude"),
                resultSet.getString("address"));
    }

    private ComplexSuggestionResult mapComplexSuggestion(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ComplexSuggestionResult(
                resultSet.getLong("complex_id"),
                resultSet.getString("complex_name"),
                resultSet.getLong("parcel_id"),
                resultSet.getString("address"));
    }

    private Double doubleOrNull(ResultSet resultSet, String column) throws SQLException {
        BigDecimal value = resultSet.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private enum SearchStage {
        EXACT,
        PREFIX,
        CONTAINS
    }
}
