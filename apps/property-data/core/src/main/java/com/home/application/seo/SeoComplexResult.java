package com.home.application.seo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SeoComplexResult(
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

    public record Breadcrumb(Long regionId, String name) {}

    public record RecentTrade(LocalDate dealDate, Long dealAmount, BigDecimal exclusiveArea, Integer floor) {}
}
