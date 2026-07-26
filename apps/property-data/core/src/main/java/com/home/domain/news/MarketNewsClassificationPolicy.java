package com.home.domain.news;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MarketNewsClassificationPolicy {

    private static final Set<String> REAL_ESTATE_ALLOWLIST = Set.of(
            "부동산", "아파트", "주택", "분양", "재건축", "재개발", "주담대", "주택담보대출", "매매", "전세", "공급", "입주", "청약", "용적률", "정비사업");
    private static final Set<String> TITLE_DECISION_ANCHORS = Set.of(
            "부동산", "아파트", "주택", "분양", "재건축", "재개발", "주담대", "주택담보대출", "매매", "전세", "공급", "입주", "청약", "용적률", "정비사업");
    private static final Set<String> INCIDENTAL_PUBLIC_HEALTH_TOPICS = Set.of("바퀴벌레", "해충", "방역", "감염병");
    private static final Set<String> CORPORATE_PERFORMANCE_TOPICS = Set.of("b2b", "실적", "매출", "영업이익", "특판");
    private static final Map<MarketNewsCategory, List<String>> CATEGORY_KEYWORDS = keywords();

    public Optional<MarketNewsCategory> classify(String title, String description) {
        String normalizedTitle = normalize(title);
        String text = normalize(normalizedTitle + " " + normalize(description));
        if (REAL_ESTATE_ALLOWLIST.stream().noneMatch(text::contains)) {
            return Optional.empty();
        }
        if (isIncidentalNonDecisionTopic(normalizedTitle)) {
            return Optional.empty();
        }
        MarketNewsCategory winner = null;
        int winnerScore = 0;
        for (MarketNewsCategory category : MarketNewsCategory.values()) {
            if (!category.isStoredCategory()) {
                continue;
            }
            int score = CATEGORY_KEYWORDS.get(category).stream()
                    .mapToInt(keyword -> occurrences(text, keyword))
                    .sum();
            if (score > winnerScore) {
                winner = category;
                winnerScore = score;
            }
        }
        return Optional.ofNullable(winner);
    }

    private static boolean isIncidentalNonDecisionTopic(String title) {
        boolean hasDecisionAnchor = TITLE_DECISION_ANCHORS.stream().anyMatch(title::contains);
        if (hasDecisionAnchor) {
            return false;
        }
        return INCIDENTAL_PUBLIC_HEALTH_TOPICS.stream().anyMatch(title::contains)
                || CORPORATE_PERFORMANCE_TOPICS.stream().anyMatch(title::contains);
    }

    private static Map<MarketNewsCategory, List<String>> keywords() {
        Map<MarketNewsCategory, List<String>> result = new EnumMap<>(MarketNewsCategory.class);
        result.put(MarketNewsCategory.POLICY, List.of("정책", "규제", "제도", "국토부", "정부", "세제"));
        result.put(MarketNewsCategory.FINANCE_LOAN, List.of("대출", "주담대", "금리", "금융", "담보"));
        result.put(MarketNewsCategory.SUPPLY_SALE, List.of("공급", "분양", "청약", "입주", "미분양"));
        result.put(MarketNewsCategory.REDEVELOPMENT, List.of("재건축", "재개발", "정비사업", "리모델링"));
        result.put(MarketNewsCategory.TRANSACTION_PRICE, List.of("매매", "거래", "가격", "실거래가", "신고가"));
        result.put(MarketNewsCategory.TRANSPORT_DEVELOPMENT, List.of("교통", "철도", "개발", "역세권", "도로"));
        return Map.copyOf(result);
    }

    private static int occurrences(String text, String keyword) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(keyword, offset)) >= 0) {
            count++;
            offset += keyword.length();
        }
        return count;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
