package com.home.infrastructure.persistence.prediction;

import com.home.application.prediction.PredictionFeature;
import com.home.application.prediction.PredictionFeatureRepository;
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

public class JdbcPredictionFeatureRepository implements PredictionFeatureRepository {

    private static final double AVERAGE_DAYS_PER_MONTH = 30.4375;
    private static final BigDecimal EXACT_AREA_TOLERANCE = new BigDecimal("0.5");

    private final JdbcClient jdbcClient;

    public JdbcPredictionFeatureRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient);
    }

    @Override
    public Optional<PredictionFeature> findFeature(Long complexId, YearMonth anchorMonth) {
        if (complexId == null || anchorMonth == null) {
            return Optional.empty();
        }

        Optional<BasisTradeRow> basis = findBasisTrade(complexId);
        if (basis.isEmpty() || !validPnu(basis.get().pnu())) {
            return Optional.empty();
        }

        BasisTradeRow basisTrade = basis.get();
        LocalDate anchorDate = anchorMonth.atDay(1);
        String legalDongCode = basisTrade.pnu().substring(0, 10);
        String sggCode = basisTrade.pnu().substring(0, 5);

        List<TradeFeatureRow> complexPrev = findComplexPrev(complexId);
        List<TradeFeatureRow> exactPrev = findExactPrev(complexId, basisTrade.exclArea());
        Map<String, Object> numeric = new HashMap<>();
        putCoreFeatures(numeric, basisTrade, anchorMonth);
        putComplexPrevFeatures(numeric, complexPrev, anchorDate);
        putExactPrevFeatures(numeric, exactPrev, basisTrade.exclArea(), anchorDate);
        putMonthlyAnchorFeatures(numeric, complexId, basisTrade.exclArea(), sggCode, anchorMonth);
        putCrossGaps(numeric);

        Map<String, String> embedding = Map.of(
                "legal_dong_code", legalDongCode,
                "sgg_code", sggCode,
                "prev_deal_gap_bucket", gapBucket(complexPrev, anchorDate));

        Object baseLog = numeric.get("log_complex_prev_price_per_m2");
        if (!(baseLog instanceof Number)) {
            return Optional.empty();
        }

        return Optional.of(new PredictionFeature(
                basisTrade.complexId(),
                basisTrade.id(),
                basisTrade.dealDate(),
                basisTrade.exclArea(),
                basisTrade.floor(),
                numeric,
                embedding,
                ((Number) baseLog).doubleValue()));
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

    private List<TradeFeatureRow> findComplexPrev(Long complexId) {
        return jdbcClient
                .sql("""
			SELECT id, deal_date, deal_amount, floor, excl_area
			FROM trade
			WHERE complex_id = :complexId
			  AND deleted_at IS NULL
			  AND excl_area IS NOT NULL
			  AND excl_area > 0
			  AND deal_amount > 0
			ORDER BY deal_date DESC, id DESC
			LIMIT 3
			""")
                .param("complexId", complexId)
                .query(this::mapTradeFeature)
                .list();
    }

    private List<TradeFeatureRow> findExactPrev(Long complexId, BigDecimal targetAreaM2) {
        return jdbcClient
                .sql("""
			SELECT id, deal_date, deal_amount, floor, excl_area
			FROM trade
			WHERE complex_id = :complexId
			  AND deleted_at IS NULL
			  AND excl_area IS NOT NULL
			  AND excl_area > 0
			  AND deal_amount > 0
			  AND abs(excl_area - :targetAreaM2) <= :tolerance
			ORDER BY deal_date DESC, id DESC
			LIMIT 3
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
        MonthlyAggregate complexLag1 = findMonthlyAggregate(
                "complex", complexId, targetAreaM2, sggCode, anchorStart.minusMonths(1), anchorStart);
        MonthlyAggregate complexLag3 = findMonthlyAggregate(
                "complex", complexId, targetAreaM2, sggCode, anchorStart.minusMonths(3), anchorStart);
        MonthlyAggregate exactLag1 = findMonthlyAggregate(
                "exact", complexId, targetAreaM2, sggCode, anchorStart.minusMonths(1), anchorStart);
        MonthlyAggregate exactLag3 = findMonthlyAggregate(
                "exact", complexId, targetAreaM2, sggCode, anchorStart.minusMonths(3), anchorStart);
        MonthlyAggregate sggLag1 =
                findMonthlyAggregate("sgg", complexId, targetAreaM2, sggCode, anchorStart.minusMonths(1), anchorStart);
        MonthlyAggregate sggLag3 =
                findMonthlyAggregate("sgg", complexId, targetAreaM2, sggCode, anchorStart.minusMonths(3), anchorStart);

        putLag1(numeric, "complex", complexLag1);
        putLag3(numeric, "complex", complexLag3);
        putLag1(numeric, "exact_area", exactLag1);
        putLag3(numeric, "exact_area", exactLag3);
        putLag1(numeric, "sgg", sggLag1);
        putLag3(numeric, "sgg", sggLag3);
    }

    private MonthlyAggregate findMonthlyAggregate(
            String scope,
            Long complexId,
            BigDecimal targetAreaM2,
            String sggCode,
            LocalDate startInclusive,
            LocalDate endExclusive) {
        String scopePredicate =
                switch (scope) {
                    case "complex" -> "t.complex_id = :complexId";
                    case "exact" -> "t.complex_id = :complexId AND abs(t.excl_area - :targetAreaM2) <= :tolerance";
                    case "sgg" -> "substring(p.pnu from 1 for 5) = :sggCode";
                    default -> throw new IllegalArgumentException("Unsupported prediction aggregate scope: " + scope);
                };
        return jdbcClient
                .sql("""
			SELECT
			    percentile_cont(0.5) WITHIN GROUP (
			        ORDER BY ln((t.deal_amount::numeric / t.excl_area)::double precision)
			    ) AS median_log_ppm,
			    avg(ln((t.deal_amount::numeric / t.excl_area)::double precision)) AS mean_log_ppm,
			    count(*) AS trade_count
			FROM trade t
			JOIN complex c ON c.id = t.complex_id
			JOIN parcel p ON p.id = c.parcel_id
			WHERE t.deleted_at IS NULL
			  AND t.excl_area IS NOT NULL
			  AND t.excl_area > 0
			  AND t.deal_amount > 0
			  AND t.deal_date >= :startInclusive
			  AND t.deal_date < :endExclusive
			  AND %s
			""".formatted(scopePredicate))
                .param("complexId", complexId)
                .param("targetAreaM2", targetAreaM2)
                .param("tolerance", EXACT_AREA_TOLERANCE)
                .param("sggCode", sggCode)
                .param("startInclusive", startInclusive)
                .param("endExclusive", endExclusive)
                .query(this::mapMonthlyAggregate)
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

    private TradeFeatureRow mapTradeFeature(ResultSet resultSet, int rowNum) throws SQLException {
        return new TradeFeatureRow(
                resultSet.getLong("id"),
                resultSet.getObject("deal_date", LocalDate.class),
                resultSet.getBigDecimal("deal_amount"),
                nullableInt(resultSet, "floor"),
                resultSet.getBigDecimal("excl_area"));
    }

    private MonthlyAggregate mapMonthlyAggregate(ResultSet resultSet, int rowNum) throws SQLException {
        return new MonthlyAggregate(
                nullableDouble(resultSet, "median_log_ppm"),
                nullableDouble(resultSet, "mean_log_ppm"),
                resultSet.getLong("trade_count"));
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
}
