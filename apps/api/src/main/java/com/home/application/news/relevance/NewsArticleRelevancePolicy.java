package com.home.application.news.relevance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class NewsArticleRelevancePolicy {

	public static final String DEFAULT_POLICY_VERSION = "rule-title-snippet-20260607-r2";

	private static final double THRESHOLD = 0.3500;
	private static final List<String> DOMAIN_TERMS = List.of(
		"부동산",
		"아파트",
		"주택",
		"집값",
		"전세",
		"월세",
		"매매",
		"분양",
		"청약",
		"재건축",
		"재개발",
		"입주",
		"실거래",
		"거래량",
		"공급",
		"미분양",
		"대출",
		"규제",
		"전세난"
	);
	private static final List<String> MARKET_SIGNAL_TERMS = List.of(
		"상승",
		"하락",
		"급등",
		"급락",
		"둔화",
		"회복",
		"거래절벽",
		"실수요",
		"영끌",
		"전세가율",
		"매물",
		"가격"
	);
	private static final List<String> MACRO_TERMS = List.of("금리", "기준금리", "물가", "환율", "채권", "경기");
	private static final List<String> GENERIC_PROPERTY_MENTION_TERMS = List.of("아파트", "주택");
	private static final List<String> NOISE_TERMS = List.of(
		"대통령",
		"총리",
		"후보자",
		"국회",
		"정당",
		"선거",
		"축구",
		"야구",
		"농구",
		"배우",
		"가수",
		"예능",
		"드라마",
		"코스피",
		"코스닥",
		"비트코인",
		"주가"
	);

	private final String policyVersion;

	private NewsArticleRelevancePolicy(String policyVersion) {
		this.policyVersion = policyVersion;
	}

	public static NewsArticleRelevancePolicy defaultPolicy() {
		return new NewsArticleRelevancePolicy(DEFAULT_POLICY_VERSION);
	}

	public String policyVersion() {
		return policyVersion;
	}

	public NewsArticleRelevanceDecision evaluate(
		NewsArticleRelevanceCandidate candidate,
		OffsetDateTime evaluatedAt
	) {
		String text = normalizedText(candidate);
		List<String> domainMatches = matchedTerms(text, DOMAIN_TERMS);
		List<String> marketSignalMatches = matchedTerms(text, MARKET_SIGNAL_TERMS);
		List<String> macroMatches = matchedTerms(text, MACRO_TERMS);
		List<String> noiseMatches = matchedTerms(text, NOISE_TERMS);
		List<String> reasonCodes = new ArrayList<>();
		Map<String, List<String>> matchedTerms = new LinkedHashMap<>();
		putIfNotEmpty(matchedTerms, "domain", domainMatches);
		putIfNotEmpty(matchedTerms, "marketSignal", marketSignalMatches);
		putIfNotEmpty(matchedTerms, "macro", macroMatches);
		putIfNotEmpty(matchedTerms, "noise", noiseMatches);

		NewsArticleRelevanceDecisionType decisionType;
		double score;
		if (hasPoliticalNoiseWithOnlyGenericPropertyMention(domainMatches, marketSignalMatches, noiseMatches)) {
			reasonCodes.add("POLITICAL_NOISE_WITH_GENERIC_PROPERTY_MENTION");
			decisionType = NewsArticleRelevanceDecisionType.SKIP_IRRELEVANT;
			score = 0.2000;
		}
		else if (!domainMatches.isEmpty()) {
			reasonCodes.add("REAL_ESTATE_DOMAIN_MATCH");
			if (!marketSignalMatches.isEmpty()) {
				reasonCodes.add("MARKET_SIGNAL_MATCH");
			}
			decisionType = NewsArticleRelevanceDecisionType.KEEP;
			score = Math.min(0.95, 0.70 + (domainMatches.size() * 0.04) + (marketSignalMatches.size() * 0.03));
		}
		else if (!noiseMatches.isEmpty()) {
			reasonCodes.add("CLEAR_NON_REAL_ESTATE_NOISE");
			decisionType = NewsArticleRelevanceDecisionType.SKIP_IRRELEVANT;
			score = 0.1000;
		}
		else if (!macroMatches.isEmpty()) {
			reasonCodes.add("AMBIGUOUS_MACRO_SIGNAL");
			decisionType = NewsArticleRelevanceDecisionType.REVIEW;
			score = THRESHOLD;
		}
		else {
			reasonCodes.add("INSUFFICIENT_REAL_ESTATE_SIGNAL");
			decisionType = NewsArticleRelevanceDecisionType.REVIEW;
			score = THRESHOLD;
		}

		return new NewsArticleRelevanceDecision(
			candidate.articleObservationId(),
			candidate.source(),
			candidate.sourceKey(),
			policyVersion,
			decisionType,
			roundScore(score),
			THRESHOLD,
			reasonCodes,
			matchedTerms,
			evaluatedAt
		);
	}

	private static String normalizedText(NewsArticleRelevanceCandidate candidate) {
		return (candidate.title() + " " + candidate.snippet()).toLowerCase(Locale.ROOT);
	}

	private static List<String> matchedTerms(String text, List<String> terms) {
		return terms.stream()
			.filter(text::contains)
			.distinct()
			.toList();
	}

	private static void putIfNotEmpty(Map<String, List<String>> target, String key, List<String> terms) {
		if (!terms.isEmpty()) {
			target.put(key, terms);
		}
	}

	private static boolean hasPoliticalNoiseWithOnlyGenericPropertyMention(
		List<String> domainMatches,
		List<String> marketSignalMatches,
		List<String> noiseMatches
	) {
		return !noiseMatches.isEmpty()
			&& !domainMatches.isEmpty()
			&& marketSignalMatches.isEmpty()
			&& GENERIC_PROPERTY_MENTION_TERMS.containsAll(domainMatches);
	}

	private static double roundScore(double value) {
		return Math.round(value * 10_000.0) / 10_000.0;
	}
}
