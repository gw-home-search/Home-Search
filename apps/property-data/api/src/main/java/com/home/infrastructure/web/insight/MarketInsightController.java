package com.home.infrastructure.web.insight;

import com.home.application.insight.read.MarketInsightQueryService;
import com.home.domain.insight.MarketInsightScopeType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class MarketInsightController {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final MarketInsightQueryService queryService;

    public MarketInsightController(MarketInsightQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/api/v1/insights/trades/latest")
    public ResponseEntity<MarketInsightResponse> latest(
            @RequestParam(defaultValue = "NATIONWIDE") MarketInsightScopeType scope,
            @RequestParam(required = false) @Pattern(regexp = "[0-9]{2}") String regionCode,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        LocalDate requestedDate = date == null ? LocalDate.now(SEOUL) : date;
        return ResponseEntity.ok(
                MarketInsightResponse.from(queryService.latest(scope, regionCode, requestedDate, limit)));
    }
}
