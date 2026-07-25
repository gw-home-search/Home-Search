package com.home.application.news.collection;

import com.home.domain.news.MarketNewsCategory;
import java.util.List;

public final class MarketNewsQueryPolicyRegistry {

    public static final String POLICY_VERSION = "NEWS_V4";

    public List<NewsQueryTemplate> nationwide() {
        return List.of(
                new NewsQueryTemplate(MarketNewsCategory.POLICY, "부동산 정책 아파트"),
                new NewsQueryTemplate(MarketNewsCategory.FINANCE_LOAN, "주택담보대출 아파트"),
                new NewsQueryTemplate(MarketNewsCategory.SUPPLY_SALE, "아파트 공급 분양"),
                new NewsQueryTemplate(MarketNewsCategory.REDEVELOPMENT, "아파트 재건축 재개발"),
                new NewsQueryTemplate(MarketNewsCategory.TRANSACTION_PRICE, "아파트 매매 거래 가격"),
                new NewsQueryTemplate(MarketNewsCategory.TRANSPORT_DEVELOPMENT, "부동산 교통 개발"));
    }

    public List<String> sido(String sidoName) {
        String normalizedSidoName = requireText(sidoName);
        return List.of(normalizedSidoName + " 아파트 부동산", normalizedSidoName + " 주택 분양");
    }

    public List<String> majorComplex(String sigunguName, String dongName, String complexName) {
        String strictLocationAndName =
                String.join(" ", requireText(sigunguName), requireText(dongName), requireText(complexName));
        return List.of(strictLocationAndName + " 아파트", strictLocationAndName);
    }

    private String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("query token is required");
        }
        return value.trim();
    }

    public record NewsQueryTemplate(MarketNewsCategory category, String query) {}
}
