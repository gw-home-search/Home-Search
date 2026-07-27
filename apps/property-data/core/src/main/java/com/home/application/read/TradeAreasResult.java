package com.home.application.read;

import java.math.BigDecimal;
import java.util.List;

public record TradeAreasResult(Long complexId, BigDecimal defaultExclArea, List<TradeAreaResult> areas) {

    public TradeAreasResult {
        areas = List.copyOf(areas);
    }
}
