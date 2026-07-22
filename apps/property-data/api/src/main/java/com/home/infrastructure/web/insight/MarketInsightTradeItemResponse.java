package com.home.infrastructure.web.insight;

import com.home.application.insight.read.MarketInsightTradeItemView;
import com.home.domain.insight.MarketInsightTradeStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record MarketInsightTradeItemResponse(
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
        MarketInsightTradeStatus tradeStatus) {

    static MarketInsightTradeItemResponse from(MarketInsightTradeItemView item) {
        return new MarketInsightTradeItemResponse(
                item.rank(),
                item.complexId(),
                item.parcelId(),
                item.complexName(),
                item.sidoName(),
                item.sigunguName(),
                item.exclArea(),
                item.dealAmount(),
                item.dealDate(),
                item.disclosedAt(),
                item.previousAmount(),
                item.previousDealDate(),
                item.deltaAmount(),
                item.deltaRate(),
                item.currentCount(),
                item.previousCount(),
                item.comparisonSampleCount(),
                item.tradeStatus());
    }
}
