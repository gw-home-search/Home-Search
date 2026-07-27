package com.home.application.tradehistory;

import com.home.application.read.TradeAreasResult;
import com.home.application.read.TradeListResult;
import com.home.application.read.TradeTrendPoint;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TradeHistoryReader {

    Optional<TradeListResult> findTradeList(Long parcelId, Long complexId, int page, int size);

    Optional<TradeListResult> findComplexTradeList(Long complexId, int page, int size);

    default Optional<TradeListResult> findComplexTradeList(Long complexId, BigDecimal exclArea, int page, int size) {
        return exclArea == null ? findComplexTradeList(complexId, page, size) : Optional.empty();
    }

    default Optional<TradeAreasResult> findTradeAreas(Long complexId) {
        return Optional.empty();
    }

    Optional<List<TradeTrendPoint>> findTradeTrend(Long parcelId, Long complexId);

    Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId);

    default Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId, BigDecimal exclArea) {
        return exclArea == null ? findComplexTradeTrend(complexId) : Optional.empty();
    }
}
