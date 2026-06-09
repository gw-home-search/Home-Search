package com.home.infrastructure.persistence.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.home.infrastructure.persistence.ingest.normalization.JdbcTradePartitionMaintenanceRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcTradePartitionMaintenanceRepositoryTest extends JdbcPostgresTestSupport {

	@Test
	@DisplayName("trade partition maintenance는 미래 연도 partition을 만들고 해당 연도 거래를 default가 아닌 연도 partition에 라우팅한다")
	void createsFutureYearPartitionAndRoutesTradeIntoIt() {
		JdbcTradePartitionMaintenanceRepository repository = new JdbcTradePartitionMaintenanceRepository(jdbcClient);
		assertThat(partitionExists("trade_2031")).isFalse();

		repository.ensureYearlyPartitions(2031, 2031);

		assertThat(partitionExists("trade_2031")).isTrue();
		seedComplex();
		jdbcClient.sql("""
			INSERT INTO raw_trade_ingest (
			    id,
			    source,
			    source_key,
			    lawd_cd,
			    deal_ymd,
			    page_no,
			    payload,
			    payload_hash,
			    status
			)
			VALUES (
			    92031,
			    'RTMS',
			    'rtms-20310115',
			    '11680',
			    '203101',
			    1,
			    '{}',
			    'hash-20310115',
			    'NORMALIZED'
			)
			""").update();
		jdbcClient.sql("""
			INSERT INTO trade (
			    complex_id,
			    deal_date,
			    deal_amount,
			    floor,
			    excl_area,
			    apt_dong,
			    source,
			    source_key,
			    complex_pk,
			    apt_seq,
			    raw_ingest_id
			)
			VALUES (
			    501,
			    DATE '2031-01-15',
			    125000,
			    12,
			    84.93,
			    '101',
			    'RTMS',
			    'rtms-20310115',
			    'COMPLEX-PK-501',
			    'APT-501',
			    92031
			)
			""").update();

		assertThat(rowPartition("rtms-20310115")).isEqualTo("trade_2031");
	}

	private boolean partitionExists(String partitionName) {
		return jdbcClient.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM pg_class child
			    JOIN pg_inherits i ON i.inhrelid = child.oid
			    JOIN pg_class parent ON parent.oid = i.inhparent
			    WHERE parent.relname = 'trade'
			      AND child.relname = :partitionName
			)
			""")
			.param("partitionName", partitionName)
			.query(Boolean.class)
			.single();
	}

	private String rowPartition(String sourceKey) {
		return jdbcClient.sql("""
			SELECT tableoid::regclass::text
			FROM trade
			WHERE source_key = :sourceKey
			""")
			.param("sourceKey", sourceKey)
			.query(String.class)
			.single();
	}
}
