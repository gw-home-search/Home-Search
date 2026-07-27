package com.home.infrastructure.persistence.tradehistory;

import com.home.application.read.TradeAreaResult;
import com.home.application.read.TradeAreasResult;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeResult;
import com.home.application.read.TradeTrendPoint;
import com.home.application.tradehistory.TradeHistoryReader;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTradeHistoryReader implements TradeHistoryReader {

    private static final String TRADE_SOURCE_SQL = """
		FROM trade t
		JOIN complex c ON c.id = t.complex_id
		WHERE (CAST(:parcelId AS BIGINT) IS NULL OR c.parcel_id = :parcelId)
		  AND (CAST(:complexId AS BIGINT) IS NULL OR c.id = :complexId)
		  AND (CAST(:exclArea AS NUMERIC) IS NULL OR t.excl_area = :exclArea)
		  AND t.deleted_at IS NULL
		""";
    private static final String TRADE_LIST_SQL = """
		SELECT
		    t.id AS trade_id,
		    t.deal_date,
		    t.excl_area,
		    t.deal_amount,
		    t.apt_dong,
		    t.floor
		""" + TRADE_SOURCE_SQL + """
		ORDER BY t.deal_date DESC, t.id DESC
		LIMIT :size OFFSET :offset
		""";
    private static final String TRADE_COUNT_SQL = "SELECT count(*)\n" + TRADE_SOURCE_SQL;
    private static final String TRADE_TREND_SQL = """
		SELECT
		    to_char(t.deal_date, 'YYYY-MM') AS month,
		    round(avg(t.deal_amount))::bigint AS avg_amount,
		    count(*) AS trade_count,
		    min(t.deal_amount) AS min_amount,
		    max(t.deal_amount) AS max_amount
		""" + TRADE_SOURCE_SQL + """
		GROUP BY to_char(t.deal_date, 'YYYY-MM')
		ORDER BY month
		""";

    private final JdbcClient jdbcClient;

    public JdbcTradeHistoryReader(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public Optional<TradeListResult> findTradeList(Long parcelId, Long complexId, int page, int size) {
        if (!hasComplexParent(parcelId, complexId)) {
            return Optional.empty();
        }
        long totalElements = countTrades(parcelId, complexId, null);
        List<TradeResult> trades = findTrades(parcelId, complexId, null, page, size);
        return Optional.of(new TradeListResult(parcelId, complexId, trades, page, size, totalElements));
    }

    @Override
    public Optional<TradeListResult> findComplexTradeList(Long complexId, int page, int size) {
        return findComplexTradeList(complexId, null, page, size);
    }

    @Override
    public Optional<TradeListResult> findComplexTradeList(Long complexId, BigDecimal exclArea, int page, int size) {
        Optional<Long> parcelId = findParcelId(complexId);
        if (parcelId.isEmpty()) {
            return Optional.empty();
        }
        long totalElements = countTrades(null, complexId, exclArea);
        List<TradeResult> trades = findTrades(null, complexId, exclArea, page, size);
        return Optional.of(new TradeListResult(parcelId.get(), complexId, trades, page, size, totalElements));
    }

    @Override
    public Optional<TradeAreasResult> findTradeAreas(Long complexId) {
        if (findParcelId(complexId).isEmpty()) {
            return Optional.empty();
        }
        BigDecimal defaultExclArea = jdbcClient
                .sql("""
			SELECT t.excl_area
			FROM trade t
			WHERE t.complex_id = :complexId
			  AND t.deleted_at IS NULL
			  AND t.excl_area > 0
			ORDER BY t.deal_date DESC, t.id DESC
			LIMIT 1
			""")
                .param("complexId", complexId)
                .query(BigDecimal.class)
                .optional()
                .orElse(null);
        List<TradeAreaResult> areas = jdbcClient
                .sql("""
			SELECT t.excl_area, count(*) AS trade_count, max(t.deal_date) AS latest_deal_date
			FROM trade t
			WHERE t.complex_id = :complexId
			  AND t.deleted_at IS NULL
			  AND t.excl_area > 0
			GROUP BY t.excl_area
			ORDER BY t.excl_area
			""")
                .param("complexId", complexId)
                .query(this::mapTradeArea)
                .list();
        return Optional.of(new TradeAreasResult(complexId, defaultExclArea, areas));
    }

    @Override
    public Optional<List<TradeTrendPoint>> findTradeTrend(Long parcelId, Long complexId) {
        return hasComplexParent(parcelId, complexId)
                ? Optional.of(findTrend(parcelId, complexId, null))
                : Optional.empty();
    }

    @Override
    public Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId) {
        return findComplexTradeTrend(complexId, null);
    }

    @Override
    public Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId, BigDecimal exclArea) {
        return findParcelId(complexId).isPresent()
                ? Optional.of(findTrend(null, complexId, exclArea))
                : Optional.empty();
    }

    private Optional<Long> findParcelId(Long complexId) {
        return jdbcClient
                .sql("""
			SELECT parcel_id
			FROM complex
			WHERE id = :complexId
			""")
                .param("complexId", complexId)
                .query(Long.class)
                .optional();
    }

    private List<TradeResult> findTrades(Long parcelId, Long complexId, BigDecimal exclArea, int page, int size) {
        return jdbcClient
                .sql(TRADE_LIST_SQL)
                .param("parcelId", parcelId)
                .param("complexId", complexId)
                .param("exclArea", exclArea)
                .param("size", size)
                .param("offset", (long) page * size)
                .query(this::mapTrade)
                .list();
    }

    private long countTrades(Long parcelId, Long complexId, BigDecimal exclArea) {
        return jdbcClient
                .sql(TRADE_COUNT_SQL)
                .param("parcelId", parcelId)
                .param("complexId", complexId)
                .param("exclArea", exclArea)
                .query(Long.class)
                .single();
    }

    private List<TradeTrendPoint> findTrend(Long parcelId, Long complexId, BigDecimal exclArea) {
        return jdbcClient
                .sql(TRADE_TREND_SQL)
                .param("parcelId", parcelId)
                .param("complexId", complexId)
                .param("exclArea", exclArea)
                .query(this::mapTradeTrend)
                .list();
    }

    private boolean hasComplexParent(Long parcelId, Long complexId) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM parcel p
			    JOIN complex c ON c.parcel_id = p.id
			    WHERE p.id = :parcelId
			      AND (CAST(:complexId AS BIGINT) IS NULL OR c.id = :complexId)
			)
			""")
                .param("parcelId", parcelId)
                .param("complexId", complexId)
                .query(Boolean.class)
                .single());
    }

    private TradeResult mapTrade(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TradeResult(
                resultSet.getLong("trade_id"),
                resultSet.getObject("deal_date", LocalDate.class),
                resultSet.getBigDecimal("excl_area"),
                resultSet.getLong("deal_amount"),
                resultSet.getString("apt_dong"),
                integerOrNull(resultSet, "floor"));
    }

    private TradeTrendPoint mapTradeTrend(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TradeTrendPoint(
                resultSet.getString("month"),
                resultSet.getLong("avg_amount"),
                resultSet.getInt("trade_count"),
                resultSet.getLong("min_amount"),
                resultSet.getLong("max_amount"));
    }

    private TradeAreaResult mapTradeArea(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TradeAreaResult(
                resultSet.getBigDecimal("excl_area"),
                resultSet.getLong("trade_count"),
                resultSet.getObject("latest_deal_date", LocalDate.class));
    }

    private Integer integerOrNull(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }
}
