package com.home.infrastructure.web.news;

import com.home.application.news.read.InvalidNewsQueryException;
import com.home.application.news.read.MarketNewsQueryService;
import com.home.domain.news.MarketNewsCategory;
import com.home.domain.news.MarketNewsScopeType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@ConditionalOnProperty(prefix = "home.news.public", name = "enabled", havingValue = "true")
public class MarketNewsController {

    private final MarketNewsQueryService queryService;

    public MarketNewsController(MarketNewsQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/api/v1/insights/news")
    public ResponseEntity<MarketNewsResponse> list(
            @RequestParam(defaultValue = "NATIONWIDE") MarketNewsScopeType scope,
            @RequestParam(required = false) @Pattern(regexp = "[0-9]{2}") String regionCode,
            @RequestParam(defaultValue = "ALL") MarketNewsCategory category,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        if (scope == MarketNewsScopeType.SIDO && (regionCode == null || regionCode.isBlank())) {
            throw new InvalidNewsQueryException("regionCode는 SIDO scope에서 필수입니다");
        }
        if (scope == MarketNewsScopeType.NATIONWIDE && regionCode != null) {
            throw new InvalidNewsQueryException("NATIONWIDE scope에는 regionCode를 사용할 수 없습니다");
        }
        return ResponseEntity.ok(
                MarketNewsResponse.from(queryService.list(scope, regionCode, category, cursor, limit)));
    }

    @GetMapping("/api/v1/complex/{complexId}/news")
    public ResponseEntity<List<MarketNewsItemResponse>> complexNews(@PathVariable @Min(1) long complexId) {
        return ResponseEntity.ok(queryService.complexNews(complexId).stream()
                .map(MarketNewsItemResponse::from)
                .toList());
    }
}
