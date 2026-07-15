package com.home.application.read;

import java.util.List;

public record TradeListResult(
        Long parcelId, Long complexId, List<TradeResult> trades, int page, int size, long totalElements) {

    public TradeListResult(Long parcelId, Long complexId, List<TradeResult> trades) {
        this(parcelId, complexId, trades, 0, trades.size(), trades.size());
    }
}
