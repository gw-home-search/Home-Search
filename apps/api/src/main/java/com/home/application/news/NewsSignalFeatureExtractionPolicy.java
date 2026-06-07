package com.home.application.news;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class NewsSignalFeatureExtractionPolicy {

	public static final String DEFAULT_EXTRACTION_VERSION = "title-snippet-signal-20260607-r1";

	private static final List<TermRule> REGION_RULES = List.of(
		new TermRule("seoul", List.of("서울", "서울시")),
		new TermRule("gangnam-gu", List.of("강남", "강남구")),
		new TermRule("seocho-gu", List.of("서초", "서초구")),
		new TermRule("songpa-gu", List.of("송파", "송파구")),
		new TermRule("mapo-gu", List.of("마포", "마포구")),
		new TermRule("yongsan-gu", List.of("용산", "용산구")),
		new TermRule("seongdong-gu", List.of("성동", "성동구")),
		new TermRule("bundang-gu", List.of("분당", "분당구")),
		new TermRule("pangyo", List.of("판교")),
		new TermRule("gyeonggi", List.of("경기", "경기도")),
		new TermRule("incheon", List.of("인천", "인천시"))
	);
	private static final List<TermRule> TOPIC_RULES = List.of(
		new TermRule("policy", List.of("정책", "규제", "대책", "세제", "세금", "정부")),
		new TermRule("supply", List.of("공급", "입주", "착공", "인허가")),
		new TermRule("reconstruction", List.of("재건축", "리모델링")),
		new TermRule("redevelopment", List.of("재개발", "정비사업")),
		new TermRule("jeonse", List.of("전세", "전셋값", "전세난", "전세가율")),
		new TermRule("rate", List.of("금리", "기준금리")),
		new TermRule("loan", List.of("대출", "주담대", "dsr", "ltv")),
		new TermRule("subscription", List.of("청약", "분양")),
		new TermRule("transaction", List.of("매매", "거래", "실거래", "거래량")),
		new TermRule("auction", List.of("경매")),
		new TermRule("unsold", List.of("미분양")),
		new TermRule("transport", List.of("철도", "지하철", "gtx", "역세권", "교통")),
		new TermRule("school", List.of("학군", "학교")),
		new TermRule("development", List.of("개발", "신도시", "택지"))
	);
	private static final List<String> UP_TERMS = List.of(
		"상승",
		"급등",
		"오름",
		"반등",
		"회복",
		"완화",
		"호재",
		"확대"
	);
	private static final List<String> DOWN_TERMS = List.of(
		"하락",
		"급락",
		"둔화",
		"침체",
		"감소",
		"위축",
		"강화",
		"악재",
		"위험"
	);

	private final String extractionVersion;

	private NewsSignalFeatureExtractionPolicy(String extractionVersion) {
		this.extractionVersion = extractionVersion;
	}

	public static NewsSignalFeatureExtractionPolicy defaultPolicy() {
		return new NewsSignalFeatureExtractionPolicy(DEFAULT_EXTRACTION_VERSION);
	}

	public String extractionVersion() {
		return extractionVersion;
	}

	public NewsSignalFeatureCommand extract(NewsSignalFeatureExtractionCandidate candidate) {
		String text = normalizedText(candidate);
		List<String> regionTags = matchedRuleTags(text, REGION_RULES);
		List<String> topicTags = matchedRuleTags(text, TOPIC_RULES);
		String impactDirection = impactDirection(text);
		String sentiment = sentiment(impactDirection);

		return new NewsSignalFeatureCommand(
			candidate.articleObservationId(),
			candidate.source(),
			candidate.sourceKey(),
			candidate.newsDateKst(),
			candidate.firstSeenAt(),
			regionTags,
			List.of(),
			topicTags,
			impactTarget(text, topicTags),
			impactDirection,
			sentiment,
			confidence(candidate, regionTags, topicTags, impactDirection),
			extractionVersion,
			evidenceLevel(candidate)
		);
	}

	private static String normalizedText(NewsSignalFeatureExtractionCandidate candidate) {
		return (Objects.toString(candidate.title(), "") + " " + Objects.toString(candidate.snippet(), ""))
			.toLowerCase(Locale.ROOT);
	}

	private static List<String> matchedRuleTags(String text, List<TermRule> rules) {
		Set<String> tags = new LinkedHashSet<>();
		for (TermRule rule : rules) {
			if (rule.matches(text)) {
				tags.add(rule.tag());
			}
		}
		return new ArrayList<>(tags);
	}

	private static String impactTarget(String text, List<String> topicTags) {
		if (topicTags.contains("jeonse")) {
			return "jeonse_price";
		}
		if (topicTags.contains("transaction") && containsAny(text, List.of("거래량", "거래절벽"))) {
			return "volume";
		}
		if (topicTags.contains("supply") || topicTags.contains("subscription")) {
			return "supply";
		}
		if (topicTags.contains("rate") || topicTags.contains("loan")) {
			return "liquidity";
		}
		if (topicTags.contains("auction") || topicTags.contains("unsold")) {
			return "risk";
		}
		return "sale_price";
	}

	private static String impactDirection(String text) {
		boolean up = containsAny(text, UP_TERMS);
		boolean down = containsAny(text, DOWN_TERMS);
		if (up && down) {
			return "mixed";
		}
		if (up) {
			return "up";
		}
		if (down) {
			return "down";
		}
		return "unknown";
	}

	private static String sentiment(String impactDirection) {
		return switch (impactDirection) {
			case "up" -> "positive";
			case "down" -> "negative";
			case "mixed" -> "mixed";
			default -> "neutral";
		};
	}

	private static double confidence(
		NewsSignalFeatureExtractionCandidate candidate,
		List<String> regionTags,
		List<String> topicTags,
		String impactDirection
	) {
		double value = 0.45;
		if (!topicTags.isEmpty()) {
			value += 0.15;
		}
		if (!regionTags.isEmpty()) {
			value += 0.10;
		}
		if (!"unknown".equals(impactDirection)) {
			value += 0.10;
		}
		if (candidate.relevanceDecisionType() == NewsArticleRelevanceDecisionType.KEEP) {
			value += 0.10;
		}
		else if (candidate.relevanceDecisionType() == NewsArticleRelevanceDecisionType.REVIEW) {
			value += 0.02;
		}
		if (!candidate.snippet().isBlank()) {
			value += 0.05;
		}
		return roundScore(Math.min(0.95, value));
	}

	private static String evidenceLevel(NewsSignalFeatureExtractionCandidate candidate) {
		return Objects.toString(candidate.snippet(), "").isBlank() ? "title" : "snippet";
	}

	private static boolean containsAny(String text, List<String> terms) {
		return terms.stream().anyMatch(text::contains);
	}

	private static double roundScore(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}

	private record TermRule(String tag, List<String> terms) {

		boolean matches(String text) {
			return terms.stream().anyMatch(text::contains);
		}
	}
}
