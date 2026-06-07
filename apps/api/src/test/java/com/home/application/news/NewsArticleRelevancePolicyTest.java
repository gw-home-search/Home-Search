package com.home.application.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsArticleRelevancePolicyTest {

	private final NewsArticleRelevancePolicy policy = NewsArticleRelevancePolicy.defaultPolicy();

	@Test
	@DisplayName("news relevance policy는 부동산 시장 뉴스는 keep으로 분류한다")
	void keepsRealEstateMarketNews() {
		NewsArticleRelevanceDecision decision = policy.evaluate(
			candidate(
				"강남은 전세난, 동탄은 영끌...수도권 집값 밀어올리는 실수요",
				"전세난과 실수요가 아파트 매매 가격 상승 압력으로 이어지고 있다"
			),
			OffsetDateTime.parse("2026-06-07T00:00:00Z")
		);

		assertThat(decision.decisionType()).isEqualTo(NewsArticleRelevanceDecisionType.KEEP);
		assertThat(decision.score()).isGreaterThanOrEqualTo(decision.threshold());
		assertThat(decision.reasonCodes()).contains("REAL_ESTATE_DOMAIN_MATCH");
		assertThat(decision.matchedTerms()).containsKey("domain");
	}

	@Test
	@DisplayName("news relevance policy는 명확한 정치성 비부동산 뉴스는 skipped irrelevant로 분류한다")
	void skipsClearPoliticalNoise() {
		NewsArticleRelevanceDecision decision = policy.evaluate(
			candidate(
				"이 대통령, 총리 후보자에 한성숙 지명",
				"대통령실은 모두의 성장을 이끌 적임자라고 설명했다"
			),
			OffsetDateTime.parse("2026-06-07T00:00:00Z")
		);

		assertThat(decision.decisionType()).isEqualTo(NewsArticleRelevanceDecisionType.SKIP_IRRELEVANT);
		assertThat(decision.score()).isLessThan(decision.threshold());
		assertThat(decision.reasonCodes()).contains("CLEAR_NON_REAL_ESTATE_NOISE");
	}

	@Test
	@DisplayName("news relevance policy는 정치성 뉴스에 아파트와 주택 단어만 섞인 경우 skipped irrelevant로 분류한다")
	void skipsPoliticalNoiseWithOnlyGenericPropertyMentions() {
		NewsArticleRelevanceDecision decision = policy.evaluate(
			candidate(
				"이 대통령, 총리 후보자에 한성숙 지명",
				"후보자는 과거 아파트와 주택 플랫폼 사업을 이끈 경력이 있다"
			),
			OffsetDateTime.parse("2026-06-07T00:00:00Z")
		);

		assertThat(decision.decisionType()).isEqualTo(NewsArticleRelevanceDecisionType.SKIP_IRRELEVANT);
		assertThat(decision.reasonCodes()).contains("POLITICAL_NOISE_WITH_GENERIC_PROPERTY_MENTION");
	}

	@Test
	@DisplayName("news relevance policy는 정부 뉴스라도 부동산 정책이면 keep으로 분류한다")
	void keepsGovernmentRealEstatePolicy() {
		NewsArticleRelevanceDecision decision = policy.evaluate(
			candidate(
				"정부, 부동산 대출 규제 완화와 공급 대책 발표",
				"아파트 매매와 전세 시장 안정을 위한 정책 패키지를 공개했다"
			),
			OffsetDateTime.parse("2026-06-07T00:00:00Z")
		);

		assertThat(decision.decisionType()).isEqualTo(NewsArticleRelevanceDecisionType.KEEP);
		assertThat(decision.reasonCodes()).contains("REAL_ESTATE_DOMAIN_MATCH");
		assertThat(decision.reasonCodes()).doesNotContain("CLEAR_NON_REAL_ESTATE_NOISE");
	}

	@Test
	@DisplayName("news relevance policy는 금리 단독 뉴스처럼 애매한 경제 뉴스는 review로 남긴다")
	void reviewsAmbiguousMacroNews() {
		NewsArticleRelevanceDecision decision = policy.evaluate(
			candidate(
				"한국은행 기준금리 동결, 시장은 관망세",
				"채권과 원화 시장은 다음 경제 지표를 기다리는 분위기다"
			),
			OffsetDateTime.parse("2026-06-07T00:00:00Z")
		);

		assertThat(decision.decisionType()).isEqualTo(NewsArticleRelevanceDecisionType.REVIEW);
		assertThat(decision.reasonCodes()).contains("AMBIGUOUS_MACRO_SIGNAL");
	}

	private static NewsArticleRelevanceCandidate candidate(String title, String snippet) {
		return new NewsArticleRelevanceCandidate(
			1L,
			"NAVER_NEWS",
			"NAVER_NEWS:sample",
			"example.com",
			title,
			snippet
		);
	}
}
