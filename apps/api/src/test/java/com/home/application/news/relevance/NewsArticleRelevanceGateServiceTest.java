package com.home.application.news.relevance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NewsArticleRelevanceGateServiceTest {

	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-07T00:00:00Z"), ZoneOffset.UTC);

	@Test
	@DisplayName("news relevance gate service는 observed 후보를 평가하고 명확한 무관 뉴스만 skipped irrelevant로 전환한다")
	void evaluatesObservedCandidatesAndSkipsOnlyClearNoise() {
		RecordingNewsArticleRelevanceRepository repository = new RecordingNewsArticleRelevanceRepository(List.of(
			candidate(1L, "이 대통령, 총리 후보자에 한성숙 지명", "대통령실은 모두의 성장을 이끌 적임자라고 설명했다"),
			candidate(2L, "강남 전세난에 수도권 아파트 집값 상승", "전세난과 실수요가 매매 가격을 밀어올린다"),
			candidate(3L, "한국은행 기준금리 동결, 시장은 관망세", "채권과 원화 시장은 경제 지표를 기다린다")
		));
		NewsArticleRelevanceGateService service = new NewsArticleRelevanceGateService(
			repository,
			NewsArticleRelevancePolicy.defaultPolicy(),
			FIXED_CLOCK
		);

		NewsArticleRelevanceGateResult result = service.evaluateObserved(10);

		assertThat(result.evaluated()).isEqualTo(3);
		assertThat(result.kept()).isEqualTo(1);
		assertThat(result.reviewed()).isEqualTo(1);
		assertThat(result.skippedIrrelevant()).isEqualTo(1);
		assertThat(result.statusUpdated()).isEqualTo(1);
		assertThat(repository.decisions).extracting(NewsArticleRelevanceDecision::decisionType)
			.containsExactly(
				NewsArticleRelevanceDecisionType.SKIP_IRRELEVANT,
				NewsArticleRelevanceDecisionType.KEEP,
				NewsArticleRelevanceDecisionType.REVIEW
			);
		assertThat(repository.skippedIds).containsExactly(1L);
		assertThat(repository.skipReasons).singleElement().asString()
			.contains("policyVersion=rule-title-snippet-20260607-r2")
			.contains("decision=SKIP_IRRELEVANT")
			.contains("CLEAR_NON_REAL_ESTATE_NOISE");
	}

	@Test
	@DisplayName("news relevance gate service는 중복 decision이면 상태 전환을 반복하지 않는다")
	void doesNotUpdateStatusWhenDecisionAlreadyExists() {
		RecordingNewsArticleRelevanceRepository repository = new RecordingNewsArticleRelevanceRepository(List.of(
			candidate(1L, "이 대통령, 총리 후보자에 한성숙 지명", "대통령실은 모두의 성장을 이끌 적임자라고 설명했다")
		));
		repository.duplicateDecision = true;
		NewsArticleRelevanceGateService service = new NewsArticleRelevanceGateService(
			repository,
			NewsArticleRelevancePolicy.defaultPolicy(),
			FIXED_CLOCK
		);

		NewsArticleRelevanceGateResult result = service.evaluateObserved(10);

		assertThat(result.evaluated()).isEqualTo(1);
		assertThat(result.decisionDuplicateSkipped()).isEqualTo(1);
		assertThat(result.statusUpdated()).isZero();
		assertThat(repository.skippedIds).isEmpty();
	}

	private static NewsArticleRelevanceCandidate candidate(long id, String title, String snippet) {
		return new NewsArticleRelevanceCandidate(
			id,
			"NAVER_NEWS",
			"NAVER_NEWS:" + id,
			"example.com",
			title,
			snippet
		);
	}

	private static class RecordingNewsArticleRelevanceRepository implements NewsArticleRelevanceRepository {

		private final List<NewsArticleRelevanceCandidate> candidates;
		private final List<NewsArticleRelevanceDecision> decisions = new ArrayList<>();
		private final List<Long> skippedIds = new ArrayList<>();
		private final List<String> skipReasons = new ArrayList<>();
		private boolean duplicateDecision;

		RecordingNewsArticleRelevanceRepository(List<NewsArticleRelevanceCandidate> candidates) {
			this.candidates = candidates;
		}

		@Override
		public List<NewsArticleRelevanceCandidate> findUnevaluatedObservedCandidates(int limit, String policyVersion) {
			return candidates.stream().limit(limit).toList();
		}

		@Override
		public boolean saveDecisionIfAbsent(NewsArticleRelevanceDecision decision) {
			if (duplicateDecision) {
				return false;
			}
			decisions.add(decision);
			return true;
		}

		@Override
		public boolean markSkippedIrrelevantIfObserved(long articleObservationId, String failureReason) {
			skippedIds.add(articleObservationId);
			skipReasons.add(failureReason);
			return true;
		}
	}
}
