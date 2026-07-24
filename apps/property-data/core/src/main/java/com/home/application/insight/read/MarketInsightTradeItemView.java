package com.home.application.insight.read;

import com.home.domain.insight.MarketInsightMetricType;
import com.home.domain.insight.MarketInsightTradeStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record MarketInsightTradeItemView(
        MarketInsightMetricType metricType,
        int rank,
        Long complexId,
        Long parcelId,
        String complexName,
        String sidoName,
        String sigunguName,
        BigDecimal exclArea,
        Long dealAmount,
        LocalDate dealDate,
        Instant disclosedAt,
        LocalDate registrationDate,
        LocalDate cancellationDate,
        Long previousAmount,
        LocalDate previousDealDate,
        Long deltaAmount,
        BigDecimal deltaRate,
        Integer currentCount,
        Integer previousCount,
        Integer comparisonSampleCount,
        MarketInsightTradeStatus tradeStatus,
        Instant canceledAt) {

    public MarketInsightTradeItemView(
            MarketInsightMetricType metricType,
            int rank,
            Long complexId,
            Long parcelId,
            String complexName,
            String sidoName,
            String sigunguName,
            BigDecimal exclArea,
            Long dealAmount,
            LocalDate dealDate,
            Instant disclosedAt,
            Long previousAmount,
            LocalDate previousDealDate,
            Long deltaAmount,
            BigDecimal deltaRate,
            Integer currentCount,
            Integer previousCount,
            Integer comparisonSampleCount,
            MarketInsightTradeStatus tradeStatus,
            Instant canceledAt) {
        this(
                metricType,
                rank,
                complexId,
                parcelId,
                complexName,
                sidoName,
                sigunguName,
                exclArea,
                dealAmount,
                dealDate,
                disclosedAt,
                null,
                null,
                previousAmount,
                previousDealDate,
                deltaAmount,
                deltaRate,
                currentCount,
                previousCount,
                comparisonSampleCount,
                tradeStatus,
                canceledAt);
    }
}
