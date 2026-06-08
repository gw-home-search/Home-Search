package com.home.application.news.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.home.application.news.relevance.NewsArticleRelevanceDecisionType;

class NewsSignalFeatureExtractionServiceTest {

	private static final OffsetDateTime FIRST_SEEN_AT = OffsetDateTime.parse("2026-06-07T09:35:00+09:00");

	@Test
	@DisplayName("news signal feature extraction service는 relevance keep 후보를 구조화 feature로 저장하고 featured로 전환한다")
	void extractsRelevantCandidateIntoStructuredFeature() {
		RecordingNewsSignalFeatureExtractionRepository repository = new RecordingNewsSignalFeatureExtractionRepository(
			List.of(candidate(1L, "강남 재건축 규제 완화에 아파트 집값 상승", "서울 강남구 재건축 단지 기대감이 커졌다"))
		);
		NewsSignalFeatureExtractionService service = new NewsSignalFeatureExtractionService(
			repository,
			NewsSignalFeatureExtractionPolicy.defaultPolicy()
		);

		NewsSignalFeatureExtractionResult result = service.extractPending(10);

		assertThat(result.evaluated()).isEqualTo(1);
		assertThat(result.extracted()).isEqualTo(1);
		assertThat(result.statusUpdated()).isEqualTo(1);
		assertThat(result.duplicateFeatureSkipped()).isZero();
		assertThat(repository.savedFeatures).singleElement().satisfies(feature -> {
			assertThat(feature.articleObservationId()).isEqualTo(1L);
			assertThat(feature.featureDateKst()).isEqualTo(LocalDate.parse("2026-06-07"));
			assertThat(feature.firstSeenAt()).isEqualTo(FIRST_SEEN_AT);
			assertThat(feature.titleKeywords())
				.containsExactly("강남", "재건축", "규제", "아파트", "집값", "상승");
			assertThat(feature.regionTags()).contains("seoul", "gangnam-gu");
			assertThat(feature.topicTags()).contains("reconstruction", "policy");
			assertThat(feature.impactTarget()).isEqualTo("sale_price");
			assertThat(feature.impactDirection()).isEqualTo("up");
			assertThat(feature.sentiment()).isEqualTo("positive");
			assertThat(feature.confidence()).isGreaterThanOrEqualTo(0.7);
			assertThat(feature.extractionVersion())
				.isEqualTo(NewsSignalFeatureExtractionPolicy.DEFAULT_EXTRACTION_VERSION);
			assertThat(feature.evidenceLevel()).isEqualTo("snippet");
		});
		assertThat(repository.featuredIds).containsExactly(1L);
	}

	@Test
	@DisplayName("news signal feature extraction service는 title keyword를 snippet이 아닌 제목에서만 추출한다")
	void extractsTitleKeywordsOnlyFromTitle() {
		RecordingNewsSignalFeatureExtractionRepository repository = new RecordingNewsSignalFeatureExtractionRepository(
			List.of(candidate(1L, "한국은행 기준금리 동결", "강남 집값 상승"))
		);
		NewsSignalFeatureExtractionService service = new NewsSignalFeatureExtractionService(
			repository,
			NewsSignalFeatureExtractionPolicy.defaultPolicy()
		);

		service.extractPending(10);

		assertThat(repository.savedFeatures).singleElement()
			.extracting(NewsSignalFeatureCommand::titleKeywords)
			.isEqualTo(List.of("금리"));
	}

	@Test
	@DisplayName("news signal feature extraction service는 중복 feature면 featured 상태 전환을 반복하지 않는다")
	void doesNotMarkFeaturedWhenFeatureAlreadyExists() {
		RecordingNewsSignalFeatureExtractionRepository repository = new RecordingNewsSignalFeatureExtractionRepository(
			List.of(candidate(1L, "강남 재건축 규제 완화", "서울 강남구 재건축 뉴스"))
		);
		repository.duplicateFeature = true;
		NewsSignalFeatureExtractionService service = new NewsSignalFeatureExtractionService(
			repository,
			NewsSignalFeatureExtractionPolicy.defaultPolicy()
		);

		NewsSignalFeatureExtractionResult result = service.extractPending(10);

		assertThat(result.evaluated()).isEqualTo(1);
		assertThat(result.extracted()).isZero();
		assertThat(result.statusUpdated()).isZero();
		assertThat(result.duplicateFeatureSkipped()).isEqualTo(1);
		assertThat(repository.featuredIds).isEmpty();
	}

	private static NewsSignalFeatureExtractionCandidate candidate(long articleObservationId, String title, String snippet) {
		return new NewsSignalFeatureExtractionCandidate(
			articleObservationId,
			"NAVER_NEWS",
			"NAVER_NEWS:" + articleObservationId,
			"example.com",
			title,
			snippet,
			LocalDate.parse("2026-06-07"),
			FIRST_SEEN_AT,
			NewsArticleRelevanceDecisionType.KEEP
		);
	}

	private static class RecordingNewsSignalFeatureExtractionRepository
		implements NewsSignalFeatureExtractionRepository {

		private final List<NewsSignalFeatureExtractionCandidate> candidates;
		private final List<NewsSignalFeatureCommand> savedFeatures = new ArrayList<>();
		private final List<Long> featuredIds = new ArrayList<>();
		private boolean duplicateFeature;

		RecordingNewsSignalFeatureExtractionRepository(List<NewsSignalFeatureExtractionCandidate> candidates) {
			this.candidates = candidates;
		}

		@Override
		public List<NewsSignalFeatureExtractionCandidate> findPendingCandidates(int limit, String extractionVersion) {
			return candidates.stream().limit(limit).toList();
		}

		@Override
		public boolean saveFeatureIfAbsent(NewsSignalFeatureCommand command) {
			if (duplicateFeature) {
				return false;
			}
			savedFeatures.add(command);
			return true;
		}

		@Override
		public boolean markFeaturedIfObserved(long articleObservationId) {
			featuredIds.add(articleObservationId);
			return true;
		}
	}
}
