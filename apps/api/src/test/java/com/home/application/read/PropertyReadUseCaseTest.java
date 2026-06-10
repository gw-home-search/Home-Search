package com.home.application.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import com.home.global.error.ResourceNotFoundException;
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
		assertThat(repository.regionComplexLimit).isEqualTo(100);
		assertThat(repository.regionComplexOffset).isEqualTo(2);
		assertThat(repository.detailComplexId).isEqualTo(501L);
		assertThat(repository.tradeComplexId).isEqualTo(501L);
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
	}

	private static class CapturingRepository implements PropertyReadRepository {

		private String searchQuery;
		private String suggestionQuery;
		private int suggestionLimit;
		private int regionComplexLimit;
		private int regionComplexOffset;
		private Long detailComplexId;
		private Long tradeComplexId;

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
		public Optional<TradeListResult> findTradeList(Long parcelId, Long complexId) {
			this.tradeComplexId = complexId;
			return Optional.of(new TradeListResult(1001L, complexId, List.of()));
		}

		@Override
		public Optional<TradeListResult> findComplexTradeList(Long complexId) {
			return Long.valueOf(404L).equals(complexId)
				? Optional.empty()
				: Optional.of(new TradeListResult(1001L, complexId, List.of()));
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
