package com.home.application.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import com.home.global.error.V1ResourceNotFoundException;
import com.home.infrastructure.web.read.dto.ParcelDetailResponse;
import com.home.infrastructure.web.read.dto.RegionDetailResponse;
import com.home.infrastructure.web.read.dto.RegionSummaryResponse;
import com.home.infrastructure.web.read.dto.SearchComplexResponse;
import com.home.infrastructure.web.read.dto.TradeListResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MvpReadUseCaseTest {

	@Test
	@DisplayName("read use case trims search queries and delegates non-empty searches")
	void trimsAndDelegatesSearchQueries() {
		CapturingRepository repository = new CapturingRepository();
		MvpReadUseCase useCase = new MvpReadUseCase(repository);

		assertThat(useCase.searchComplexes("  Sample  "))
			.singleElement()
			.extracting(SearchComplexResponse::complexName)
			.isEqualTo("Sample Apartment");
		assertThat(repository.searchQuery).isEqualTo("Sample");
	}

	@Test
	@DisplayName("read use case returns empty search result without querying repository for blank input")
	void blankSearchDoesNotQueryRepository() {
		CapturingRepository repository = new CapturingRepository();
		MvpReadUseCase useCase = new MvpReadUseCase(repository);

		assertThat(useCase.searchComplexes(" ")).isEmpty();
		assertThat(useCase.searchComplexes(null)).isEmpty();
		assertThat(repository.searchQuery).isNull();
	}

	@Test
	@DisplayName("read use case delegates region, detail, and trade reads")
	void delegatesReadApis() {
		CapturingRepository repository = new CapturingRepository();
		MvpReadUseCase useCase = new MvpReadUseCase(repository);

		assertThat(useCase.getRootRegions()).containsExactly(new RegionSummaryResponse(1L, "Seoul"));
		assertThat(useCase.getRegionDetail(1L).name()).isEqualTo("Seoul");
		assertThat(useCase.getParcelDetail(1001L).name()).isEqualTo("Sample Apartment");
		assertThat(useCase.getTradeList(1001L).trades()).isEmpty();
	}

	@Test
	@DisplayName("read use case converts missing parent paths to V1 not found exceptions")
	void missingParentsThrowV1NotFound() {
		MvpReadUseCase useCase = new MvpReadUseCase(new EmptyMvpReadRepository());

		assertThatThrownBy(() -> useCase.getRegionDetail(404L))
			.isInstanceOf(V1ResourceNotFoundException.class)
			.hasMessageContaining("region not found");
		assertThatThrownBy(() -> useCase.getParcelDetail(404L))
			.isInstanceOf(V1ResourceNotFoundException.class)
			.hasMessageContaining("parcel detail not found");
		assertThatThrownBy(() -> useCase.getTradeList(404L))
			.isInstanceOf(V1ResourceNotFoundException.class)
			.hasMessageContaining("parcel trade parent not found");
	}

	@Test
	@DisplayName("empty read repository returns empty public read seams")
	void emptyRepositoryReturnsEmptyReadSeams() {
		EmptyMvpReadRepository repository = new EmptyMvpReadRepository();

		assertThat(repository.searchComplexes("Sample")).isEmpty();
		assertThat(repository.findRootRegions()).isEmpty();
		assertThat(repository.findRegionDetail(1L)).isEmpty();
		assertThat(repository.findParcelDetail(1001L)).isEmpty();
		assertThat(repository.findTradeList(1001L)).isEmpty();
	}

	private static class CapturingRepository implements MvpReadRepository {

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
