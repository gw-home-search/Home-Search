package com.home.infrastructure.persistence.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcMvpReadRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("search/region/detail/trade read API는 V1 core table로 backing된다")
	void readsMvpMapExplorationDataFromV1Tables() {
		seedMvpExplorationData();
		JdbcMvpReadRepository repository = new JdbcMvpReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("sample"))
			.singleElement()
			.satisfies(result -> {
				assertThat(result.complexId()).isEqualTo(501L);
				assertThat(result.complexName()).isEqualTo("Sample Apartment");
				assertThat(result.parcelId()).isEqualTo(1001L);
				assertThat(result.latitude()).isEqualTo(37.5123);
				assertThat(result.longitude()).isEqualTo(127.0456);
				assertThat(result.address()).isEqualTo("Sample address");
			});

		assertThat(repository.findRootRegions())
			.singleElement()
			.satisfies(region -> {
				assertThat(region.id()).isEqualTo(1L);
				assertThat(region.name()).isEqualTo("Seoul");
			});

		assertThat(repository.findRegionDetail(1L))
			.hasValueSatisfying(region -> {
				assertThat(region.latitude()).isEqualTo(37.5663);
				assertThat(region.longitude()).isEqualTo(126.9780);
				assertThat(region.children())
					.extracting("name")
					.containsExactly("Gangnam-gu");
			});

		assertThat(repository.findParcelDetail(1001L))
			.hasValueSatisfying(detail -> {
				assertThat(detail.parcelId()).isEqualTo(1001L);
				assertThat(detail.name()).isEqualTo("Sample Apartment");
				assertThat(detail.tradeName()).isEqualTo("Sample trade name");
				assertThat(detail.unitCnt()).isEqualTo(740);
				assertThat(detail.platArea()).isEqualByComparingTo(new BigDecimal("12345.67"));
				assertThat(detail.useDate()).isEqualTo(LocalDate.of(2015, 3, 20));
			});

		assertThat(repository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> {
				assertThat(tradeList.parcelId()).isEqualTo(1001L);
				assertThat(tradeList.trades())
					.extracting("tradeId")
					.containsExactly(9002L, 9001L);
				assertThat(tradeList.trades().get(0).dealAmount()).isEqualTo(130000L);
			});
	}

	@Test
	@DisplayName("trade read API는 parcel에 complex가 있지만 trade가 없으면 empty trade list를 반환한다")
	void tradeListReturnsEmptyWhenParcelAndComplexExistWithoutTrades() {
		seedComplex();
		JdbcMvpReadRepository repository = new JdbcMvpReadRepository(jdbcClient);

		assertThat(repository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> {
				assertThat(tradeList.parcelId()).isEqualTo(1001L);
				assertThat(tradeList.trades()).isEmpty();
			});
	}

	@Test
	@DisplayName("detail/trade read API는 parcel 또는 complex parent path가 없으면 empty가 된다")
	void missingParentPathReturnsEmpty() {
		seedMvpExplorationData();
		JdbcMvpReadRepository repository = new JdbcMvpReadRepository(jdbcClient);

		assertThat(repository.findParcelDetail(404L)).isEmpty();
		assertThat(repository.findTradeList(404L)).isEmpty();
	}
}
