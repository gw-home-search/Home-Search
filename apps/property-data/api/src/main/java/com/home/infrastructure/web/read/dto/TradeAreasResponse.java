package com.home.infrastructure.web.read.dto;

import com.home.application.read.TradeAreasResult;
import java.math.BigDecimal;
import java.util.List;

public record TradeAreasResponse(Long complexId, BigDecimal defaultExclArea, List<TradeAreaResponse> areas) {

    public static TradeAreasResponse from(TradeAreasResult result) {
        return new TradeAreasResponse(
                result.complexId(),
                result.defaultExclArea(),
                result.areas().stream().map(TradeAreaResponse::from).toList());
    }
}
