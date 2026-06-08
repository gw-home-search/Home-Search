package com.home.application.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsArticleRelevanceDecisionTypeTest {

	@Test
	@DisplayName("news relevance decision type은 결정별 confidence 보정값을 직접 제공한다")
	void decisionTypeOwnsSignalConfidenceBonus() {
		assertThat(NewsArticleRelevanceDecisionType.KEEP.signalConfidenceBonus()).isEqualTo(0.10);
		assertThat(NewsArticleRelevanceDecisionType.REVIEW.signalConfidenceBonus()).isEqualTo(0.02);
		assertThat(NewsArticleRelevanceDecisionType.SKIP_IRRELEVANT.signalConfidenceBonus()).isZero();
	}

	@Test
	@DisplayName("news relevance decision type은 결정 분류 predicate를 직접 제공한다")
	void decisionTypeOwnsPredicates() {
		assertThat(NewsArticleRelevanceDecisionType.KEEP.isKeep()).isTrue();
		assertThat(NewsArticleRelevanceDecisionType.KEEP.isReview()).isFalse();
		assertThat(NewsArticleRelevanceDecisionType.REVIEW.isReview()).isTrue();
		assertThat(NewsArticleRelevanceDecisionType.REVIEW.isSkipIrrelevant()).isFalse();
		assertThat(NewsArticleRelevanceDecisionType.SKIP_IRRELEVANT.isSkipIrrelevant()).isTrue();
	}
}
