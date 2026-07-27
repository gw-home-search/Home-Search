package com.home.infrastructure.web.tradehistory;

import com.home.application.tradehistory.TradeHistoryService;
import com.home.infrastructure.web.read.dto.TradeAreasResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;
import com.home.infrastructure.web.read.dto.TradeTrendResponse;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class TradeHistoryController {

    private final TradeHistoryService tradeHistoryService;

    public TradeHistoryController(TradeHistoryService tradeHistoryService) {
        this.tradeHistoryService = tradeHistoryService;
    }

    @GetMapping("/api/v1/trade/{parcelId}")
    public ResponseEntity<TradeListResponse> getTradeList(
            @PathVariable @Positive Long parcelId,
            @RequestParam(required = false) @Positive Long complexId,
            @RequestParam(required = false) @PositiveOrZero Integer page,
            @RequestParam(required = false) @Positive Integer size) {
        return ResponseEntity.ok(
                TradeListResponse.from(tradeHistoryService.getTradeList(parcelId, complexId, page, size)));
    }

    @GetMapping("/api/v1/complex/{complexId}/trades")
    public ResponseEntity<TradeListResponse> getComplexTradeList(
            @PathVariable @Positive Long complexId,
            @RequestParam(required = false) @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal exclArea,
            @RequestParam(required = false) @PositiveOrZero Integer page,
            @RequestParam(required = false) @Positive Integer size) {
        return ResponseEntity.ok(
                TradeListResponse.from(tradeHistoryService.getComplexTradeList(complexId, exclArea, page, size)));
    }

    @GetMapping("/api/v1/complex/{complexId}/trade-areas")
    public ResponseEntity<TradeAreasResponse> getTradeAreas(@PathVariable @Positive Long complexId) {
        return ResponseEntity.ok(TradeAreasResponse.from(tradeHistoryService.getTradeAreas(complexId)));
    }

    @GetMapping("/api/v1/trade/{parcelId}/trend")
    public ResponseEntity<List<TradeTrendResponse>> getTradeTrend(
            @PathVariable @Positive Long parcelId, @RequestParam(required = false) @Positive Long complexId) {
        return ResponseEntity.ok(tradeHistoryService.getTradeTrend(parcelId, complexId).stream()
                .map(TradeTrendResponse::from)
                .toList());
    }

    @GetMapping("/api/v1/complex/{complexId}/trade-trend")
    public ResponseEntity<List<TradeTrendResponse>> getComplexTradeTrend(
            @PathVariable @Positive Long complexId,
            @RequestParam(required = false) @DecimalMin("0.01") @Digits(integer = 8, fraction = 2)
                    BigDecimal exclArea) {
        return ResponseEntity.ok(tradeHistoryService.getComplexTradeTrend(complexId, exclArea).stream()
                .map(TradeTrendResponse::from)
                .toList());
    }
}
