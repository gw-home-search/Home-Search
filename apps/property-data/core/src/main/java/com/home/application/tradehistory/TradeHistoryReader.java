package com.home.application.tradehistory;

import java.util.List;
import java.util.Optional;

import com.home.application.read.TradeListResult;
import com.home.application.read.TradeTrendPoint;

public interface TradeHistoryReader {

	Optional<TradeListResult> findTradeList(Long parcelId, Long complexId, int page, int size);

	Optional<TradeListResult> findComplexTradeList(Long complexId, int page, int size);

	Optional<List<TradeTrendPoint>> findTradeTrend(Long parcelId, Long complexId);

	Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId);
}
