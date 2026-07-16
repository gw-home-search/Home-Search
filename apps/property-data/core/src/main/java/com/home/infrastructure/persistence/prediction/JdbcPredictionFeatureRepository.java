package com.home.infrastructure.persistence.prediction;

import com.home.application.prediction.PredictionBasis;
import com.home.application.prediction.PredictionBasisReader;
import com.home.application.prediction.PredictionFeatureSnapshot;
import com.home.application.prediction.PredictionFeatureSnapshotReader;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPredictionFeatureRepository implements PredictionBasisReader, PredictionFeatureSnapshotReader {

    private static final double AVERAGE_DAYS_PER_MONTH = 30.4375;
    private static final BigDecimal EXACT_AREA_TOLERANCE = new BigDecimal("0.5");

    private final JdbcClient jdbcClient;

    public JdbcPredictionFeatureRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public Optional<PredictionBasis> findBasis(Long complexId) {
        if (complexId == null) {
            return Optional.empty();
        }
        return findBasisTrade(complexId).filter(basis -> validPnu(basis.pnu())).map(this::toBasis);
    }

    @Override
    public Optional<PredictionFeatureSnapshot> readSnapshot(PredictionBasis predictionBasis, YearMonth anchorMonth) {
        if (predictionBasis == null || anchorMonth == null || !validPnu(predictionBasis.pnu())) {
            return Optional.empty();
        }

        BasisTradeRow basisTrade = toRow(predictionBasis);
        LocalDate anchorDate = anchorMonth.atDay(1);
        String legalDongCode = basisTrade.pnu().substring(0, 10);
        String sggCode = basisTrade.pnu().substring(0, 5);

        List<TradeFeatureRow> previousTrades = findPreviousTrades(basisTrade.complexId(), basisTrade.exclArea());
        List<TradeFeatureRow> complexPrev = previousTrades.stream().limit(3).toList();
        List<TradeFeatureRow> exactPrev = previousTrades.stream()
                .filter(row -> row.exclArea() != null
                        && row.exclArea().subtract(basisTrade.exclArea()).abs().compareTo(EXACT_AREA_TOLERANCE) <= 0)
                .limit(3)
                .toList();
        Map<String, Object> numeric = new HashMap<>();
        putCoreFeatures(numeric, basisTrade, anchorMonth);
        putComplexPrevFeatures(numeric, complexPrev, anchorDate);
        putExactPrevFeatures(numeric, exactPrev, basisTrade.exclArea(), anchorDate);
        putMonthlyAnchorFeatures(numeric, basisTrade.complexId(), basisTrade.exclArea(), sggCode, anchorMonth);
        putCrossGaps(numeric);

        Map<String, String> embedding = Map.of(
                "legal_dong_code", legalDongCode,
                "sgg_code", sggCode,
                "prev_deal_gap_bucket", gapBucket(complexPrev, anchorDate));

        Object baseLog = numeric.get("log_complex_prev_price_per_m2");
        if (!(baseLog instanceof Number)) {
            return Optional.empty();
        }

        return Optional.of(new PredictionFeatureSnapshot(numeric, embedding, ((Number) baseLog).doubleValue()));
    }

    private Optional<BasisTradeRow> findBasisTrade(Long complexId) {
        return jdbcClient
                .sql("""
			SELECT
			    t.id,
			    t.complex_id,
			    t.deal_date,
			    t.deal_amount,
			    t.floor,
			    t.excl_area,
			    p.pnu,
			    c.use_date
			FROM trade t
			JOIN complex c ON c.id = t.complex_id
			JOIN parcel p ON p.id = c.parcel_id
			WHERE t.complex_id = :complexId
			  AND t.deleted_at IS NULL
			  AND t.excl_area IS NOT NULL
			  AND t.excl_area > 0
			  AND t.deal_amount > 0
			ORDER BY t.deal_date DESC, t.id DESC
			LIMIT 1
			""")
                .param("complexId", complexId)
                .query(this::mapBasisTrade)
                .optional();
    }

    private List<TradeFeatureRow> findPreviousTrades(Long complexId, BigDecimal targetAreaM2) {
        return jdbcClient
                .sql("""
			WITH ranked AS (
			    SELECT
			        id,
			        deal_date,
			        deal_amount,
			        floor,
			        excl_area,
			        row_number() OVER (ORDER BY deal_date DESC, id DESC) AS complex_rank,
			        sum(CASE WHEN abs(excl_area - :targetAreaM2) <= :tolerance THEN 1 ELSE 0 END)
			            OVER (ORDER BY deal_date DESC, id DESC) AS exact_rank
			    FROM trade
			    WHERE complex_id = :complexId
			      AND deleted_at IS NULL
			      AND excl_area IS NOT NULL
			      AND excl_area > 0
			      AND deal_amount > 0
			)
			SELECT id, deal_date, deal_amount, floor, excl_area
			FROM ranked
			WHERE complex_rank <= 3
			   OR (abs(excl_area - :targetAreaM2) <= :tolerance AND exact_rank <= 3)
			ORDER BY deal_date DESC, id DESC
			""")
                .param("complexId", complexId)
                .param("targetAreaM2", targetAreaM2)
                .param("tolerance", EXACT_AREA_TOLERANCE)
                .query(this::mapTradeFeature)
                .list();
    }

    private void putCoreFeatures(Map<String, Object> numeric, BasisTradeRow basisTrade, YearMonth anchorMonth) {
        int floor = basisTrade.floor() == null ? 0 : basisTrade.floor();
        put(numeric, "area_m2", basisTrade.exclArea());
        put(numeric, "floor", floor);
        put(numeric, "is_basement_floor", floor < 0 ? 1 : 0);
        put(
                numeric,
                "age_years",
                basisTrade.useDate() == null
                        ? null
                        : anchorMonth.getYear() - basisTrade.useDate().getYear());
    }

    private void putComplexPrevFeatures(Map<String, Object> numeric, List<TradeFeatureRow> rows, LocalDate anchorDate) {
        TradeFeatureRow prev1 = rowAt(rows, 0);
        TradeFeatureRow prev2 = rowAt(rows, 1);
        TradeFeatureRow prev3 = rowAt(rows, 2);

        put(numeric, "log_complex_prev_price_per_m2", logPricePerM2(prev1));
        put(numeric, "complex_prev_missing", prev1 == null ? 1 : 0);
        put(numeric, "prev_deal_gap_months", gapMonths(anchorDate, prev1));
        put(numeric, "log_complex_prev2_price_per_m2", logPricePerM2(prev2));
        put(numeric, "prev2_missing", prev2 == null ? 1 : 0);
        put(numeric, "prev2_gap_months", gapMonths(anchorDate, prev2));
        put(numeric, "prev1_prev2_log_return", logReturn(prev1, prev2));
        put(numeric, "prev1_prev2_gap_months", gapMonthsBetween(prev1, prev2));
        put(numeric, "log_complex_prev3_price_per_m2", logPricePerM2(prev3));
        put(numeric, "prev3_missing", prev3 == null ? 1 : 0);
        put(numeric, "prev3_gap_months", gapMonths(anchorDate, prev3));
        put(numeric, "prev2_prev3_log_return", logReturn(prev2, prev3));
        put(numeric, "prev2_prev3_gap_months", gapMonthsBetween(prev2, prev3));
        putLogSummary(numeric, "complex_prev3", rows);
    }

    private void putExactPrevFeatures(
            Map<String, Object> numeric, List<TradeFeatureRow> rows, BigDecimal targetAreaM2, LocalDate anchorDate) {
        TradeFeatureRow prev1 = rowAt(rows, 0);
        TradeFeatureRow prev2 = rowAt(rows, 1);
        TradeFeatureRow prev3 = rowAt(rows, 2);

        put(numeric, "log_exact_prev1_price_per_m2", logPricePerM2(prev1));
        put(numeric, "exact_prev1_missing", prev1 == null ? 1 : 0);
        put(numeric, "exact_prev1_gap_months", gapMonths(anchorDate, prev1));
        put(numeric, "log_exact_prev2_price_per_m2", logPricePerM2(prev2));
        put(numeric, "exact_prev2_missing", prev2 == null ? 1 : 0);
        put(numeric, "exact_prev2_gap_months", gapMonths(anchorDate, prev2));
        put(numeric, "exact_prev1_prev2_log_return", logReturn(prev1, prev2));
        put(numeric, "exact_prev1_prev2_gap_months", gapMonthsBetween(prev1, prev2));
        put(numeric, "exact_prev1_area_abs_diff", areaAbsDiff(targetAreaM2, prev1));
        put(numeric, "exact_prev2_area_abs_diff", areaAbsDiff(targetAreaM2, prev2));
        put(
                numeric,
                "wide_prev1_present_exact_missing",
                numeric.get("complex_prev_missing").equals(0) && prev1 == null ? 1 : 0);
        put(numeric, "log_exact_prev3_price_per_m2", logPricePerM2(prev3));
        put(numeric, "exact_prev3_missing", prev3 == null ? 1 : 0);
        put(numeric, "exact_prev3_gap_months", gapMonths(anchorDate, prev3));
        put(numeric, "exact_prev2_prev3_log_return", logReturn(prev2, prev3));
        put(numeric, "exact_prev2_prev3_gap_months", gapMonthsBetween(prev2, prev3));
        put(numeric, "exact_prev3_area_abs_diff", areaAbsDiff(targetAreaM2, prev3));
        putLogSummary(numeric, "exact_prev3", rows);
    }

    private void putMonthlyAnchorFeatures(
            Map<String, Object> numeric,
            Long complexId,
            BigDecimal targetAreaM2,
            String sggCode,
            YearMonth anchorMonth) {
        LocalDate anchorStart = anchorMonth.atDay(1);
        MonthlyAggregateSet aggregates = findMonthlyAggregates(
                complexId, targetAreaM2, sggCode, anchorStart.minusMonths(3), anchorStart.minusMonths(1), anchorStart);
        putLag1(numeric, "complex", aggregates.complexLag1());
        putLag3(numeric, "complex", aggregates.complexLag3());
        putLag1(numeric, "exact_area", aggregates.exactLag1());
        putLag3(numeric, "exact_area", aggregates.exactLag3());
        putLag1(numeric, "sgg", aggregates.sggLag1());
        putLag3(numeric, "sgg", aggregates.sggLag3());
    }

    private MonthlyAggregateSet findMonthlyAggregates(
            Long complexId,
            BigDecimal targetAreaM2,
            String sggCode,
            LocalDate lag3Start,
            LocalDate lag1Start,
            LocalDate anchorStart) {
        return jdbcClient
                .sql("""
			WITH base AS (
			    SELECT
			        t.complex_id,
			        t.deal_date,
			        t.excl_area,
			        substring(p.pnu from 1 for 5) AS sgg_code,
			        ln((t.deal_amount::numeric / t.excl_area)::double precision) AS log_ppm
			    FROM trade t
			    JOIN complex c ON c.id = t.complex_id
			    JOIN parcel p ON p.id = c.parcel_id
			    WHERE t.deleted_at IS NULL
			      AND t.excl_area IS NOT NULL
			      AND t.excl_area > 0
			      AND t.deal_amount > 0
			      AND t.deal_date >= :lag3Start
			      AND t.deal_date < :anchorStart
			)
			SELECT
			    percentile_cont(0.5) WITHIN GROUP (ORDER BY log_ppm)
			        FILTER (WHERE complex_id = :complexId AND deal_date >= :lag1Start) AS complex_lag1_median,
			    count(*) FILTER (WHERE complex_id = :complexId AND deal_date >= :lag1Start) AS complex_lag1_count,
			    percentile_cont(0.5) WITHIN GROUP (ORDER BY log_ppm)
			        FILTER (WHERE complex_id = :complexId) AS complex_lag3_median,
			    avg(log_ppm) FILTER (WHERE complex_id = :complexId) AS complex_lag3_mean,
			    count(*) FILTER (WHERE complex_id = :complexId) AS complex_lag3_count,
			    percentile_cont(0.5) WITHIN GROUP (ORDER BY log_ppm)
			        FILTER (WHERE complex_id = :complexId AND abs(excl_area - :targetAreaM2) <= :tolerance AND deal_date >= :lag1Start) AS exact_lag1_median,
			    count(*) FILTER (WHERE complex_id = :complexId AND abs(excl_area - :targetAreaM2) <= :tolerance AND deal_date >= :lag1Start) AS exact_lag1_count,
			    percentile_cont(0.5) WITHIN GROUP (ORDER BY log_ppm)
			        FILTER (WHERE complex_id = :complexId AND abs(excl_area - :targetAreaM2) <= :tolerance) AS exact_lag3_median,
			    avg(log_ppm) FILTER (WHERE complex_id = :complexId AND abs(excl_area - :targetAreaM2) <= :tolerance) AS exact_lag3_mean,
			    count(*) FILTER (WHERE complex_id = :complexId AND abs(excl_area - :targetAreaM2) <= :tolerance) AS exact_lag3_count,
			    percentile_cont(0.5) WITHIN GROUP (ORDER BY log_ppm)
			        FILTER (WHERE sgg_code = :sggCode AND deal_date >= :lag1Start) AS sgg_lag1_median,
			    count(*) FILTER (WHERE sgg_code = :sggCode AND deal_date >= :lag1Start) AS sgg_lag1_count,
			    percentile_cont(0.5) WITHIN GROUP (ORDER BY log_ppm)
			        FILTER (WHERE sgg_code = :sggCode) AS sgg_lag3_median,
			    avg(log_ppm) FILTER (WHERE sgg_code = :sggCode) AS sgg_lag3_mean,
			    count(*) FILTER (WHERE sgg_code = :sggCode) AS sgg_lag3_count
			FROM base
			""")
                .param("complexId", complexId)
                .param("targetAreaM2", targetAreaM2)
                .param("tolerance", EXACT_AREA_TOLERANCE)
                .param("sggCode", sggCode)
                .param("lag3Start", lag3Start)
                .param("lag1Start", lag1Start)
                .param("anchorStart", anchorStart)
                .query(this::mapMonthlyAggregateSet)
                .single();
    }

    private void putLag1(Map<String, Object> numeric, String prefix, MonthlyAggregate aggregate) {
        put(numeric, prefix + "_lag1m_log_median_ppm", aggregate.medianLogPpm());
        put(numeric, prefix + "_lag1m_missing", aggregate.count() == 0 ? 1 : 0);
    }

    private void putLag3(Map<String, Object> numeric, String prefix, MonthlyAggregate aggregate) {
        put(numeric, prefix + "_lag3m_log_median_ppm", aggregate.medianLogPpm());
        put(numeric, prefix + "_lag3m_log_mean_ppm", aggregate.meanLogPpm());
        put(numeric, prefix + "_lag3m_count", aggregate.count());
        put(numeric, prefix + "_lag3m_missing", aggregate.count() == 0 ? 1 : 0);
    }

    private void putCrossGaps(Map<String, Object> numeric) {
        put(
                numeric,
                "prev1_vs_complex_lag3m_log_gap",
                subtract(numeric.get("log_complex_prev_price_per_m2"), numeric.get("complex_lag3m_log_median_ppm")));
        put(
                numeric,
                "exact_prev1_vs_exact_lag3m_log_gap",
                subtract(numeric.get("log_exact_prev1_price_per_m2"), numeric.get("exact_area_lag3m_log_median_ppm")));
    }

    private void putLogSummary(Map<String, Object> numeric, String prefix, List<TradeFeatureRow> rows) {
        List<Double> logs = rows.stream()
                .map(this::logPricePerM2)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        put(numeric, prefix + "_log_count", logs.size());
        put(
                numeric,
                prefix + "_log_mean",
                logs.isEmpty()
                        ? null
                        : logs.stream()
                                .mapToDouble(Double::doubleValue)
                                .average()
                                .orElseThrow());
        put(numeric, prefix + "_log_median", median(logs));
        put(numeric, prefix + "_log_std", std(logs));
        put(numeric, prefix + "_log_spread", logs.isEmpty() ? null : logs.get(logs.size() - 1) - logs.get(0));
    }

    private BasisTradeRow mapBasisTrade(ResultSet resultSet, int rowNum) throws SQLException {
        return new BasisTradeRow(
                resultSet.getLong("id"),
                resultSet.getLong("complex_id"),
                resultSet.getObject("deal_date", LocalDate.class),
                resultSet.getBigDecimal("deal_amount"),
                nullableInt(resultSet, "floor"),
                resultSet.getBigDecimal("excl_area"),
                resultSet.getString("pnu"),
                resultSet.getObject("use_date", LocalDate.class));
    }

    private PredictionBasis toBasis(BasisTradeRow row) {
        return new PredictionBasis(
                row.complexId(),
                row.id(),
                row.dealDate(),
                row.dealAmount(),
                row.floor(),
                row.exclArea(),
                row.pnu(),
                row.useDate());
    }

    private BasisTradeRow toRow(PredictionBasis basis) {
        return new BasisTradeRow(
                basis.tradeId(),
                basis.complexId(),
                basis.dealDate(),
                basis.dealAmount(),
                basis.floor(),
                basis.areaM2(),
                basis.pnu(),
                basis.useDate());
    }

    private TradeFeatureRow mapTradeFeature(ResultSet resultSet, int rowNum) throws SQLException {
        return new TradeFeatureRow(
                resultSet.getLong("id"),
                resultSet.getObject("deal_date", LocalDate.class),
                resultSet.getBigDecimal("deal_amount"),
                nullableInt(resultSet, "floor"),
                resultSet.getBigDecimal("excl_area"));
    }

    private MonthlyAggregateSet mapMonthlyAggregateSet(ResultSet resultSet, int rowNum) throws SQLException {
        return new MonthlyAggregateSet(
                lag1(resultSet, "complex"),
                lag3(resultSet, "complex"),
                lag1(resultSet, "exact"),
                lag3(resultSet, "exact"),
                lag1(resultSet, "sgg"),
                lag3(resultSet, "sgg"));
    }

    private MonthlyAggregate lag1(ResultSet resultSet, String scope) throws SQLException {
        return new MonthlyAggregate(
                nullableDouble(resultSet, scope + "_lag1_median"), null, resultSet.getLong(scope + "_lag1_count"));
    }

    private MonthlyAggregate lag3(ResultSet resultSet, String scope) throws SQLException {
        return new MonthlyAggregate(
                nullableDouble(resultSet, scope + "_lag3_median"),
                nullableDouble(resultSet, scope + "_lag3_mean"),
                resultSet.getLong(scope + "_lag3_count"));
    }

    private Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private void put(Map<String, Object> numeric, String key, Object value) {
        if (value instanceof BigDecimal decimal) {
            numeric.put(key, decimal.doubleValue());
            return;
        }
        numeric.put(key, value);
    }

    private TradeFeatureRow rowAt(List<TradeFeatureRow> rows, int index) {
        return rows.size() > index ? rows.get(index) : null;
    }

    private Double logPricePerM2(TradeFeatureRow row) {
        if (row == null
                || row.dealAmount() == null
                || row.exclArea() == null
                || row.exclArea().signum() <= 0) {
            return null;
        }
        return Math.log(row.dealAmount().doubleValue() / row.exclArea().doubleValue());
    }

    private Double logReturn(TradeFeatureRow newer, TradeFeatureRow older) {
        Double newerLog = logPricePerM2(newer);
        Double olderLog = logPricePerM2(older);
        return newerLog == null || olderLog == null ? null : newerLog - olderLog;
    }

    private Double areaAbsDiff(BigDecimal targetAreaM2, TradeFeatureRow row) {
        return row == null || row.exclArea() == null
                ? null
                : targetAreaM2.subtract(row.exclArea()).abs().doubleValue();
    }

    private Double gapMonths(LocalDate targetDate, TradeFeatureRow source) {
        if (targetDate == null || source == null || source.dealDate() == null) {
            return null;
        }
        long days = Math.abs(ChronoUnit.DAYS.between(source.dealDate(), targetDate));
        return days / AVERAGE_DAYS_PER_MONTH;
    }

    private Double gapMonthsBetween(TradeFeatureRow newer, TradeFeatureRow older) {
        if (newer == null || older == null || newer.dealDate() == null || older.dealDate() == null) {
            return null;
        }
        long days = Math.abs(ChronoUnit.DAYS.between(older.dealDate(), newer.dealDate()));
        return days / AVERAGE_DAYS_PER_MONTH;
    }

    private Double subtract(Object left, Object right) {
        if (!(left instanceof Number leftNumber) || !(right instanceof Number rightNumber)) {
            return null;
        }
        return leftNumber.doubleValue() - rightNumber.doubleValue();
    }

    private Double median(List<Double> sortedValues) {
        if (sortedValues.isEmpty()) {
            return null;
        }
        int midpoint = sortedValues.size() / 2;
        if (sortedValues.size() % 2 == 1) {
            return sortedValues.get(midpoint);
        }
        return (sortedValues.get(midpoint - 1) + sortedValues.get(midpoint)) / 2.0;
    }

    private Double std(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double variance = values.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2.0))
                .average()
                .orElseThrow();
        return Math.sqrt(variance);
    }

    private String gapBucket(List<TradeFeatureRow> rows, LocalDate anchorDate) {
        TradeFeatureRow prev1 = rowAt(rows, 0);
        if (prev1 == null || prev1.dealDate() == null) {
            return "missing";
        }
        long days = Math.abs(ChronoUnit.DAYS.between(prev1.dealDate(), anchorDate));
        if (days <= 30) {
            return "0-30";
        }
        if (days <= 90) {
            return "31-90";
        }
        if (days <= 180) {
            return "91-180";
        }
        if (days <= 365) {
            return "181-365";
        }
        return "366+";
    }

    private boolean validPnu(String pnu) {
        return pnu != null && pnu.matches("\\d{19}");
    }

    private record BasisTradeRow(
            Long id,
            Long complexId,
            LocalDate dealDate,
            BigDecimal dealAmount,
            Integer floor,
            BigDecimal exclArea,
            String pnu,
            LocalDate useDate) {}

    private record TradeFeatureRow(
            Long id, LocalDate dealDate, BigDecimal dealAmount, Integer floor, BigDecimal exclArea) {}

    private record MonthlyAggregate(Double medianLogPpm, Double meanLogPpm, long count) {}

    private record MonthlyAggregateSet(
            MonthlyAggregate complexLag1,
            MonthlyAggregate complexLag3,
            MonthlyAggregate exactLag1,
            MonthlyAggregate exactLag3,
            MonthlyAggregate sggLag1,
            MonthlyAggregate sggLag3) {}
}
