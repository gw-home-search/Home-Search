package com.home.infrastructure.web.read.dto;

import com.home.application.read.TradeAreaResult;
import java.math.BigDecimal;
import java.time.LocalDate;

public record TradeAreaResponse(BigDecimal exclArea, long tradeCount, LocalDate latestDealDate) {

    public static TradeAreaResponse from(TradeAreaResult result) {
        return new TradeAreaResponse(result.exclArea(), result.tradeCount(), result.latestDealDate());
    }
}
