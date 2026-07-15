package com.home.infrastructure.web.tradehistory;

import com.home.application.tradehistory.TradeHistoryService;
import com.home.infrastructure.web.read.dto.TradeListResponse;
import com.home.infrastructure.web.read.dto.TradeTrendResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TradeHistoryController {

    private final TradeHistoryService tradeHistoryService;

    public TradeHistoryController(TradeHistoryService tradeHistoryService) {
        this.tradeHistoryService = tradeHistoryService;
    }

    @GetMapping("/api/v1/trade/{parcelId}")
    public ResponseEntity<TradeListResponse> getTradeList(
            @PathVariable Long parcelId,
            @RequestParam(required = false) Long complexId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(
                TradeListResponse.from(tradeHistoryService.getTradeList(parcelId, complexId, page, size)));
    }

    @GetMapping("/api/v1/complex/{complexId}/trades")
    public ResponseEntity<TradeListResponse> getComplexTradeList(
            @PathVariable Long complexId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(
                TradeListResponse.from(tradeHistoryService.getComplexTradeList(complexId, page, size)));
    }

    @GetMapping("/api/v1/trade/{parcelId}/trend")
    public ResponseEntity<List<TradeTrendResponse>> getTradeTrend(
            @PathVariable Long parcelId, @RequestParam(required = false) Long complexId) {
        return ResponseEntity.ok(tradeHistoryService.getTradeTrend(parcelId, complexId).stream()
                .map(TradeTrendResponse::from)
                .toList());
    }

    @GetMapping("/api/v1/complex/{complexId}/trade-trend")
    public ResponseEntity<List<TradeTrendResponse>> getComplexTradeTrend(@PathVariable Long complexId) {
        return ResponseEntity.ok(tradeHistoryService.getComplexTradeTrend(complexId).stream()
                .map(TradeTrendResponse::from)
                .toList());
    }
}
