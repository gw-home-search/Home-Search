package com.home.infrastructure.persistence.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcPropertyReadRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("search/region/detail/trade read API는 baseline core table로 backing된다")
	void readsPropertyMapExplorationDataFromBaselineTables() {
		seedPropertyExplorationData();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("sample"))
			.singleElement()
			.satisfies(result -> {
				assertThat(result.complexId()).isEqualTo(501L);
				assertThat(result.complexName()).isEqualTo("Sample trade name");
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
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> {
				assertThat(tradeList.parcelId()).isEqualTo(1001L);
				assertThat(tradeList.trades()).isEmpty();
			});
	}

	@Test
	@DisplayName("search API complexName은 레거시처럼 trade_name을 name보다 우선한다")
	void searchComplexesUsesLegacyDisplayNamePolicy() {
		seedComplex();
		jdbcClient.sql("""
			UPDATE complex
			SET name = 'Building Register Name',
			    trade_name = 'Legacy Trade Display Name'
			WHERE id = 501
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("legacy"))
			.singleElement()
			.satisfies(result -> assertThat(result.complexName()).isEqualTo("Legacy Trade Display Name"));
	}

	@Test
	@DisplayName("search API complexName은 trade_name이 blank이면 name으로 fallback한다")
	void searchComplexesFallsBackToNameWhenTradeNameIsBlank() {
		seedComplex();
		jdbcClient.sql("""
			UPDATE complex
			SET name = 'Building Register Name',
			    trade_name = '   '
			WHERE id = 501
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("building"))
			.singleElement()
			.satisfies(result -> assertThat(result.complexName()).isEqualTo("Building Register Name"));
	}

	@Test
	@DisplayName("search API는 보존된 complex alias도 검색 evidence로 사용한다")
	void searchComplexesFindsComplexByPreservedAlias() {
		seedComplex();
		jdbcClient.sql("""
			UPDATE complex
			SET name = 'Building Register Name',
			    trade_name = 'Official Trade Name'
			WHERE id = 501
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex_name_alias (
			    complex_id,
			    alias_type,
			    alias_name,
			    normalized_name,
			    source
			)
			VALUES (
			    501,
			    'RTMS_APT_NAME',
			    'RTMS Wobbly Name',
			    'rtmswobblyname',
			    'RTMS'
			)
			""").update();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("wobbly"))
			.singleElement()
			.satisfies(result -> {
				assertThat(result.complexId()).isEqualTo(501L);
				assertThat(result.complexName()).isEqualTo("Official Trade Name");
				assertThat(result.parcelId()).isEqualTo(1001L);
			});
		assertThat(repository.searchComplexes("rtmswobbly"))
			.singleElement()
			.satisfies(result -> assertThat(result.complexId()).isEqualTo(501L));
	}

	@Test
	@DisplayName("detail/trade read API는 parcel 또는 complex parent path가 없으면 empty가 된다")
	void missingParentPathReturnsEmpty() {
		seedPropertyExplorationData();
		JdbcPropertyReadRepository repository = new JdbcPropertyReadRepository(jdbcClient);

		assertThat(repository.findParcelDetail(404L)).isEmpty();
		assertThat(repository.findTradeList(404L)).isEmpty();
	}
}
