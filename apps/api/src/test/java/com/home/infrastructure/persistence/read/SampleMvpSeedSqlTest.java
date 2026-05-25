package com.home.infrastructure.persistence.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.JdbcPostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class SampleMvpSeedSqlTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("sample MVP seed SQL은 idempotent하며 map detail/trade read를 지원한다")
	void sampleMvpSeedSqlIsIdempotentAndQueryable() {
		runSampleSeed();
		runSampleSeed();

		JdbcMvpReadRepository repository = new JdbcMvpReadRepository(jdbcClient);

		assertThat(repository.searchComplexes("Sample Apartment"))
			.singleElement()
			.satisfies(result -> {
				assertThat(result.complexId()).isEqualTo(501L);
				assertThat(result.parcelId()).isEqualTo(1001L);
			});
		assertThat(repository.findTradeList(1001L))
			.hasValueSatisfying(tradeList -> assertThat(tradeList.trades())
				.extracting("tradeId")
				.containsExactly(9002L, 9001L));
		assertThat(jdbcClient.sql("""
			SELECT count(*)
			FROM raw_trade_ingest
			WHERE status = 'MATCH_FAILED'
			  AND failure_reason IS NOT NULL
			""")
			.query(Long.class)
			.single()).isEqualTo(1L);
	}

	private void runSampleSeed() {
		new ResourceDatabasePopulator(new ClassPathResource("db/seed/local/R__sample_mvp_data.sql"))
			.execute(dataSource);
	}
}
