package com.home.infrastructure.web.seo;

import com.home.application.seo.SeoComplexResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SeoComplexResponse(
        Long complexId,
        String name,
        String address,
        boolean indexable,
        Integer dongCount,
        Integer unitCount,
        LocalDate useApprovalDate,
        boolean hasBuildingInfo,
        List<Breadcrumb> breadcrumbs,
        List<RecentTrade> recentTrades) {
    static SeoComplexResponse from(SeoComplexResult result) {
        return new SeoComplexResponse(
                result.complexId(),
                result.name(),
                result.address(),
                result.indexable(),
                result.dongCount(),
                result.unitCount(),
                result.useApprovalDate(),
                result.hasBuildingInfo(),
                result.breadcrumbs().stream()
                        .map(item -> new Breadcrumb(item.regionId(), item.name()))
                        .toList(),
                result.recentTrades().stream()
                        .map(item ->
                                new RecentTrade(item.dealDate(), item.dealAmount(), item.exclusiveArea(), item.floor()))
                        .toList());
    }

    public record Breadcrumb(Long regionId, String name) {}

    public record RecentTrade(LocalDate dealDate, Long dealAmount, BigDecimal exclusiveArea, Integer floor) {}
}
