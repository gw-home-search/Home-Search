package com.home.application.news.signal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class NewsSignalFeatureExtractionPolicy {

	public static final String DEFAULT_EXTRACTION_VERSION = "title-snippet-signal-20260607-r2";

	private static final int MAX_TITLE_KEYWORDS = 12;
	private static final List<TitleKeywordRule> TITLE_KEYWORD_RULES = List.of(
		new TitleKeywordRule("강남", List.of("강남구", "강남")),
		new TitleKeywordRule("서초", List.of("서초구", "서초")),
		new TitleKeywordRule("송파", List.of("송파구", "송파")),
		new TitleKeywordRule("마포", List.of("마포구", "마포")),
		new TitleKeywordRule("용산", List.of("용산구", "용산")),
		new TitleKeywordRule("성동", List.of("성동구", "성동")),
		new TitleKeywordRule("분당", List.of("분당구", "분당")),
		new TitleKeywordRule("판교", List.of("판교")),
		new TitleKeywordRule("동탄", List.of("동탄")),
		new TitleKeywordRule("서울", List.of("서울시", "서울")),
		new TitleKeywordRule("수도권", List.of("수도권")),
		new TitleKeywordRule("경기", List.of("경기도", "경기")),
		new TitleKeywordRule("인천", List.of("인천시", "인천")),
		new TitleKeywordRule("부동산", List.of("부동산")),
		new TitleKeywordRule("아파트", List.of("아파트")),
		new TitleKeywordRule("집값", List.of("집값")),
		new TitleKeywordRule("가격", List.of("가격")),
		new TitleKeywordRule("전세난", List.of("전세난")),
		new TitleKeywordRule("전세가율", List.of("전세가율")),
		new TitleKeywordRule("전세", List.of("전셋값", "전세")),
		new TitleKeywordRule("월세", List.of("월세")),
		new TitleKeywordRule("매매", List.of("매매")),
		new TitleKeywordRule("실거래", List.of("실거래")),
		new TitleKeywordRule("거래량", List.of("거래량")),
		new TitleKeywordRule("거래절벽", List.of("거래절벽")),
		new TitleKeywordRule("매물", List.of("매물")),
		new TitleKeywordRule("청약", List.of("청약")),
		new TitleKeywordRule("분양", List.of("분양")),
		new TitleKeywordRule("입주", List.of("입주")),
		new TitleKeywordRule("공급", List.of("공급")),
		new TitleKeywordRule("미분양", List.of("미분양")),
		new TitleKeywordRule("재건축", List.of("재건축")),
		new TitleKeywordRule("재개발", List.of("재개발")),
		new TitleKeywordRule("정비사업", List.of("정비사업")),
		new TitleKeywordRule("규제", List.of("규제")),
		new TitleKeywordRule("대출", List.of("주담대", "대출")),
		new TitleKeywordRule("금리", List.of("기준금리", "금리")),
		new TitleKeywordRule("DSR", List.of("dsr")),
		new TitleKeywordRule("LTV", List.of("ltv")),
		new TitleKeywordRule("GTX", List.of("gtx")),
		new TitleKeywordRule("역세권", List.of("역세권")),
		new TitleKeywordRule("영끌", List.of("영끌")),
		new TitleKeywordRule("실수요", List.of("실수요")),
		new TitleKeywordRule("상승", List.of("상승", "급등", "오름", "반등")),
		new TitleKeywordRule("하락", List.of("하락", "급락", "둔화", "침체"))
	);
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
		List<String> titleKeywords = titleKeywords(candidate.title());
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
			titleKeywords,
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

	private static List<String> titleKeywords(String title) {
		String text = Objects.toString(title, "").toLowerCase(Locale.ROOT);
		if (text.isBlank()) {
			return List.of();
		}
		List<KeywordMatch> matches = new ArrayList<>();
		for (TitleKeywordRule rule : TITLE_KEYWORD_RULES) {
			rule.firstMatch(text).ifPresent(matches::add);
		}
		matches.sort(Comparator
			.comparingInt(KeywordMatch::start)
			.thenComparing(Comparator.comparingInt(KeywordMatch::length).reversed())
			.thenComparing(KeywordMatch::keyword));

		List<String> keywords = new ArrayList<>();
		List<KeywordMatch> acceptedMatches = new ArrayList<>();
		for (KeywordMatch match : matches) {
			if (keywords.contains(match.keyword()) || overlapsAccepted(match, acceptedMatches)) {
				continue;
			}
			keywords.add(match.keyword());
			acceptedMatches.add(match);
			if (keywords.size() == MAX_TITLE_KEYWORDS) {
				break;
			}
		}
		return keywords;
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
		value += candidate.relevanceDecisionType().signalConfidenceBonus();
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

	private record TitleKeywordRule(String keyword, List<String> terms) {

		java.util.Optional<KeywordMatch> firstMatch(String text) {
			KeywordMatch earliest = null;
			for (String term : terms) {
				String normalizedTerm = term.toLowerCase(Locale.ROOT);
				int start = text.indexOf(normalizedTerm);
				if (start < 0) {
					continue;
				}
				KeywordMatch candidate = new KeywordMatch(keyword, start, normalizedTerm.length());
				if (earliest == null
					|| candidate.start() < earliest.start()
					|| (candidate.start() == earliest.start() && candidate.length() > earliest.length())) {
					earliest = candidate;
				}
			}
			return java.util.Optional.ofNullable(earliest);
		}
	}

	private record KeywordMatch(String keyword, int start, int length) {

		int endExclusive() {
			return start + length;
		}
	}

	private static boolean overlapsAccepted(KeywordMatch match, List<KeywordMatch> acceptedMatches) {
		for (KeywordMatch acceptedMatch : acceptedMatches) {
			if (match.start() < acceptedMatch.endExclusive()
				&& acceptedMatch.start() < match.endExclusive()) {
				return true;
			}
		}
		return false;
	}
}
