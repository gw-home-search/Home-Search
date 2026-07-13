package com.home.application.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import com.home.application.read.ParcelDetailResult;
import com.home.application.read.RegionDetailResult;
import com.home.application.read.RegionSummaryResult;
import com.home.application.read.SearchComplexResult;
import com.home.application.read.TradeListResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PropertyReadUseCaseTest {

	@Test
	@DisplayName("read use case는 search query를 trim하고 non-empty search를 위임한다")
	void trimsAndDelegatesSearchQueries() {
		CapturingRepository repository = new CapturingRepository();
		PropertyReadUseCase useCase = new PropertyReadUseCase(repository);

		assertThat(useCase.searchComplexes("  Sample  "))
			.singleElement()
			.extracting(SearchComplexResult::complexName)
			.isEqualTo("Sample Apartment");
		assertThat(repository.searchQuery).isEqualTo("Sample");
		assertThat(useCase.suggestComplexes("  Sample  "))
			.singleElement()
			.extracting(ComplexSuggestionResult::complexName)
			.isEqualTo("Sample Apartment");
		assertThat(repository.suggestionQuery).isEqualTo("Sample");
		assertThat(repository.suggestionLimit).isEqualTo(8);
	}

	@Test
	@DisplayName("read use case는 blank input에서 repository query 없이 empty search result를 반환한다")
	void blankSearchDoesNotQueryRepository() {
		CapturingRepository repository = new CapturingRepository();
		PropertyReadUseCase useCase = new PropertyReadUseCase(repository);

		assertThat(useCase.searchComplexes(" ")).isEmpty();
		assertThat(useCase.searchComplexes(null)).isEmpty();
		assertThat(useCase.suggestComplexes(" ")).isEmpty();
		assertThat(useCase.suggestComplexes(null)).isEmpty();
		assertThat(repository.searchQuery).isNull();
		assertThat(repository.suggestionQuery).isNull();
	}

	@Test
	@DisplayName("read use case는 100자·8 token 경계를 허용하고 초과 query는 repository 전에 거부한다")
	void rejectsOversizedSearchQueriesBeforeRepositoryAccess() {
		CapturingRepository repository = new CapturingRepository();
		PropertyReadUseCase useCase = new PropertyReadUseCase(repository);

		assertThat(useCase.searchComplexes("가".repeat(100))).hasSize(1);
		assertThat(repository.searchQuery).isEqualTo("가".repeat(100));
		assertThat(useCase.searchComplexes("가 나 다 라 마 바 사 아")).hasSize(1);
		assertThat(repository.searchQuery).isEqualTo("가 나 다 라 마 바 사 아");

		CapturingRepository rejectedRepository = new CapturingRepository();
		PropertyReadUseCase rejectedUseCase = new PropertyReadUseCase(rejectedRepository);
		assertThatThrownBy(() -> rejectedUseCase.searchComplexes("가".repeat(101)))
			.isInstanceOf(InvalidReadRequestException.class);
		assertThatThrownBy(() -> rejectedUseCase.searchComplexes("가 나 다 라 마 바 사 아 자"))
			.isInstanceOf(InvalidReadRequestException.class);
		assertThat(rejectedRepository.searchQuery).isNull();
	}

	@Test
	@DisplayName("read use case는 대소문자 반복 token을 제거해 repository에 한 번만 전달한다")
	void deduplicatesRepeatedSearchTokensBeforeRepositoryAccess() {
		CapturingRepository repository = new CapturingRepository();
		PropertyReadUseCase useCase = new PropertyReadUseCase(repository);

		assertThat(useCase.searchComplexes("  대림   대림 DAELIM daelim  ")).hasSize(1);

		assertThat(repository.searchQuery).isEqualTo("대림 DAELIM");
	}

	@Test
	@DisplayName("read use case는 region/detail/trade와 확장 read API를 위임한다")
	void delegatesReadApis() {
		CapturingRepository repository = new CapturingRepository();
		PropertyReadUseCase useCase = new PropertyReadUseCase(repository);

		assertThat(useCase.getRootRegions()).containsExactly(new RegionSummaryResult(1L, "Seoul"));
		assertThat(useCase.getRegionDetail(1L).name()).isEqualTo("Seoul");
		assertThat(useCase.getRegionComplexes(1L, 500, 2))
			.singleElement()
			.extracting(ComplexSummaryResult::complexId)
			.isEqualTo(501L);
		assertThat(useCase.getParcelDetail(1001L).name()).isEqualTo("Sample Apartment");
		assertThat(useCase.getParcelComplexes(1001L))
			.singleElement()
			.extracting(ComplexSummaryResult::complexName)
			.isEqualTo("Sample Apartment");
		assertThat(useCase.getComplexDetail(501L).complexId()).isEqualTo(501L);
		assertThat(useCase.getTradeList(1001L).trades()).isEmpty();
		assertThat(useCase.getComplexTradeList(501L).complexId()).isEqualTo(501L);
		assertThat(useCase.getParcelDetail(1001L, 501L).complexId()).isEqualTo(501L);
		assertThat(useCase.getTradeList(1001L, 501L).complexId()).isEqualTo(501L);
		assertThat(useCase.getTradeTrend(1001L, 501L))
			.singleElement()
			.extracting(TradeTrendPoint::month)
			.isEqualTo("2024-11");
		assertThat(useCase.getComplexTradeTrend(501L)).hasSize(1);
		assertThat(repository.regionComplexLimit).isEqualTo(100);
		assertThat(repository.regionComplexOffset).isEqualTo(2);
		assertThat(repository.detailComplexId).isEqualTo(501L);
		assertThat(repository.tradeComplexId).isEqualTo(501L);
		assertThat(repository.tradePage).isEqualTo(0);
		assertThat(repository.tradeSize).isEqualTo(25);
		assertThat(repository.trendComplexId).isEqualTo(501L);
	}

	@Test
	@DisplayName("read use case는 trade page/size를 검증하고 size 상한을 적용한다")
	void validatesAndCapsTradePageRequest() {
		CapturingRepository repository = new CapturingRepository();
		PropertyReadUseCase useCase = new PropertyReadUseCase(repository);

		assertThatThrownBy(() -> useCase.getTradeList(1001L, null, -1, 10))
			.isInstanceOf(InvalidReadRequestException.class)
			.hasMessageContaining("page must be greater than or equal to 0");
		assertThatThrownBy(() -> useCase.getTradeList(1001L, null, 0, 0))
			.isInstanceOf(InvalidReadRequestException.class)
			.hasMessageContaining("size must be greater than 0");
		assertThatThrownBy(() -> useCase.getComplexTradeList(501L, -1, 10))
			.isInstanceOf(InvalidReadRequestException.class)
			.hasMessageContaining("page must be greater than or equal to 0");

		useCase.getTradeList(1001L, null, 3, 500);
		assertThat(repository.tradePage).isEqualTo(3);
		assertThat(repository.tradeSize).isEqualTo(100);

		useCase.getComplexTradeList(501L, 2, 25);
		assertThat(repository.tradePage).isEqualTo(2);
		assertThat(repository.tradeSize).isEqualTo(25);
	}

	@Test
	@DisplayName("read use case는 region complex page limit과 offset을 검증한다")
	void validatesRegionComplexPageRequest() {
		PropertyReadUseCase useCase = new PropertyReadUseCase(new CapturingRepository());

		assertThatThrownBy(() -> useCase.getRegionComplexes(1L, 0, 0))
			.isInstanceOf(InvalidReadRequestException.class)
			.hasMessageContaining("limit must be greater than 0");
		assertThatThrownBy(() -> useCase.getRegionComplexes(1L, 10, -1))
			.isInstanceOf(InvalidReadRequestException.class)
			.hasMessageContaining("offset must be greater than or equal to 0");
	}

	@Test
	@DisplayName("read use case는 missing parent path를 resource not found exception으로 변환한다")
	void missingParentsThrowResourceNotFound() {
		PropertyReadUseCase useCase = new PropertyReadUseCase(new EmptyPropertyReadRepository());

		assertThatThrownBy(() -> useCase.getRegionDetail(404L))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("region not found");
		assertThatThrownBy(() -> useCase.getParcelDetail(404L))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("parcel detail not found");
		assertThatThrownBy(() -> useCase.getTradeList(404L))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("parcel trade parent not found");
		assertThatThrownBy(() -> useCase.getRegionComplexes(404L, 10, 0))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("region not found");
		assertThatThrownBy(() -> useCase.getParcelComplexes(404L))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("parcel not found");
		assertThatThrownBy(() -> useCase.getComplexDetail(404L))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("complex detail not found");
		assertThatThrownBy(() -> useCase.getComplexTradeList(404L))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("complex trade parent not found");
		assertThatThrownBy(() -> useCase.getTradeTrend(404L, null))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("parcel trade parent not found");
		assertThatThrownBy(() -> useCase.getComplexTradeTrend(404L))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("complex trade parent not found");
	}

	@Test
	@DisplayName("empty read repository는 empty public read seam을 반환한다")
	void emptyRepositoryReturnsEmptyReadSeams() {
		EmptyPropertyReadRepository repository = new EmptyPropertyReadRepository();

		assertThat(repository.searchComplexes("Sample")).isEmpty();
		assertThat(repository.suggestComplexes("Sample", 8)).isEmpty();
		assertThat(repository.findRootRegions()).isEmpty();
		assertThat(repository.findRegionDetail(1L)).isEmpty();
		assertThat(repository.findRegionComplexes(1L, 10, 0)).isEmpty();
		assertThat(repository.findParcelDetail(1001L)).isEmpty();
		assertThat(repository.findParcelComplexes(1001L)).isEmpty();
		assertThat(repository.findComplexDetail(501L)).isEmpty();
		assertThat(repository.findTradeList(1001L)).isEmpty();
		assertThat(repository.findComplexTradeList(501L)).isEmpty();
		assertThat(repository.findTradeTrend(1001L, null)).isEmpty();
		assertThat(repository.findComplexTradeTrend(501L)).isEmpty();
	}

	private static class CapturingRepository implements PropertyReadRepository {

		private String searchQuery;
		private String suggestionQuery;
		private int suggestionLimit;
		private int regionComplexLimit;
		private int regionComplexOffset;
		private Long detailComplexId;
		private Long tradeComplexId;
		private int tradePage;
		private int tradeSize;
		private Long trendComplexId;

		@Override
		public List<SearchComplexResult> searchComplexes(String query) {
			this.searchQuery = query;
			return List.of(new SearchComplexResult(
				501L,
				"Sample Apartment",
				1001L,
				37.5123,
				127.0456,
				"Sample address"
			));
		}

		@Override
		public List<ComplexSuggestionResult> suggestComplexes(String query, int limit) {
			this.suggestionQuery = query;
			this.suggestionLimit = limit;
			return List.of(new ComplexSuggestionResult(501L, "Sample Apartment", 1001L, "Sample address"));
		}

		@Override
		public List<RegionSummaryResult> findRootRegions() {
			return List.of(new RegionSummaryResult(1L, "Seoul"));
		}

		@Override
		public Optional<RegionDetailResult> findRegionDetail(Long regionId) {
			return Optional.of(new RegionDetailResult(1L, "Seoul", 37.5663, 126.9780, List.of()));
		}

		@Override
		public Optional<List<ComplexSummaryResult>> findRegionComplexes(Long regionId, int limit, int offset) {
			this.regionComplexLimit = limit;
			this.regionComplexOffset = offset;
			return Long.valueOf(404L).equals(regionId)
				? Optional.empty()
				: Optional.of(List.of(summary()));
		}

		@Override
		public Optional<ParcelDetailResult> findParcelDetail(Long parcelId, Long complexId) {
			this.detailComplexId = complexId;
			return Optional.of(new ParcelDetailResult(
				1001L,
				complexId,
				37.5123,
				127.0456,
				"Sample address",
				"Sample trade name",
				"Sample Apartment",
				8,
				740,
				null,
				null,
				null,
				null,
				null,
				null
			));
		}

		@Override
		public Optional<List<ComplexSummaryResult>> findParcelComplexes(Long parcelId) {
			return Long.valueOf(404L).equals(parcelId)
				? Optional.empty()
				: Optional.of(List.of(summary()));
		}

		@Override
		public Optional<ParcelDetailResult> findComplexDetail(Long complexId) {
			return Long.valueOf(404L).equals(complexId)
				? Optional.empty()
				: findParcelDetail(1001L, complexId);
		}

		@Override
		public Optional<TradeListResult> findTradeList(Long parcelId, Long complexId, int page, int size) {
			this.tradeComplexId = complexId;
			this.tradePage = page;
			this.tradeSize = size;
			return Optional.of(new TradeListResult(1001L, complexId, List.of()));
		}

		@Override
		public Optional<TradeListResult> findComplexTradeList(Long complexId, int page, int size) {
			this.tradePage = page;
			this.tradeSize = size;
			return Long.valueOf(404L).equals(complexId)
				? Optional.empty()
				: Optional.of(new TradeListResult(1001L, complexId, List.of()));
		}

		@Override
		public Optional<List<TradeTrendPoint>> findTradeTrend(Long parcelId, Long complexId) {
			this.trendComplexId = complexId;
			return Optional.of(List.of(new TradeTrendPoint("2024-11", 89500L, 3, 80000L, 92000L)));
		}

		@Override
		public Optional<List<TradeTrendPoint>> findComplexTradeTrend(Long complexId) {
			return Long.valueOf(404L).equals(complexId)
				? Optional.empty()
				: Optional.of(List.of(new TradeTrendPoint("2024-11", 89500L, 3, 80000L, 92000L)));
		}

		private ComplexSummaryResult summary() {
			return new ComplexSummaryResult(
				501L,
				"Sample Apartment",
				1001L,
				37.5123,
				127.0456,
				"Sample address",
				8,
				740,
				null
			);
		}
	}
}
