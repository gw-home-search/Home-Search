package com.home.application.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import com.home.global.error.ResourceNotFoundException;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;
import com.home.infrastructure.web.read.dto.SearchComplexResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;

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
			.extracting(SearchComplexResponse::complexName)
			.isEqualTo("Sample Apartment");
		assertThat(repository.searchQuery).isEqualTo("Sample");
	}

	@Test
	@DisplayName("read use case는 blank input에서 repository query 없이 empty search result를 반환한다")
	void blankSearchDoesNotQueryRepository() {
		CapturingRepository repository = new CapturingRepository();
		PropertyReadUseCase useCase = new PropertyReadUseCase(repository);

		assertThat(useCase.searchComplexes(" ")).isEmpty();
		assertThat(useCase.searchComplexes(null)).isEmpty();
		assertThat(repository.searchQuery).isNull();
	}

	@Test
	@DisplayName("read use case는 region/detail/trade read를 위임한다")
	void delegatesReadApis() {
		CapturingRepository repository = new CapturingRepository();
		PropertyReadUseCase useCase = new PropertyReadUseCase(repository);

		assertThat(useCase.getRootRegions()).containsExactly(new RegionSummaryResponse(1L, "Seoul"));
		assertThat(useCase.getRegionDetail(1L).name()).isEqualTo("Seoul");
		assertThat(useCase.getParcelDetail(1001L).name()).isEqualTo("Sample Apartment");
		assertThat(useCase.getTradeList(1001L).trades()).isEmpty();
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
	}

	@Test
	@DisplayName("empty read repository는 empty public read seam을 반환한다")
	void emptyRepositoryReturnsEmptyReadSeams() {
		EmptyPropertyReadRepository repository = new EmptyPropertyReadRepository();

		assertThat(repository.searchComplexes("Sample")).isEmpty();
		assertThat(repository.findRootRegions()).isEmpty();
		assertThat(repository.findRegionDetail(1L)).isEmpty();
		assertThat(repository.findParcelDetail(1001L)).isEmpty();
		assertThat(repository.findTradeList(1001L)).isEmpty();
	}

	private static class CapturingRepository implements PropertyReadRepository {

		private String searchQuery;

		@Override
		public List<SearchComplexResponse> searchComplexes(String query) {
			this.searchQuery = query;
			return List.of(new SearchComplexResponse(
				501L,
				"Sample Apartment",
				1001L,
				37.5123,
				127.0456,
				"Sample address"
			));
		}

		@Override
		public List<RegionSummaryResponse> findRootRegions() {
			return List.of(new RegionSummaryResponse(1L, "Seoul"));
		}

		@Override
		public Optional<RegionDetailResponse> findRegionDetail(Long regionId) {
			return Optional.of(new RegionDetailResponse(1L, "Seoul", 37.5663, 126.9780, List.of()));
		}

		@Override
		public Optional<ParcelDetailResponse> findParcelDetail(Long parcelId) {
			return Optional.of(new ParcelDetailResponse(
				1001L,
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
		public Optional<TradeListResponse> findTradeList(Long parcelId) {
			return Optional.of(new TradeListResponse(1001L, List.of()));
		}
	}
}
