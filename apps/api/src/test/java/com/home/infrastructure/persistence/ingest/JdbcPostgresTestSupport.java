package com.home.infrastructure.persistence.ingest;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
public abstract class JdbcPostgresTestSupport {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
	);

	protected DataSource dataSource;
	protected JdbcClient jdbcClient;
	protected TransactionTemplate transactionTemplate;

	@BeforeEach
	void resetDatabase() {
		dataSource = dataSource();
		Flyway flyway = Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration/api")
			.schemas("public", "reference")
			.defaultSchema("public")
			.cleanDisabled(false)
			.load();
		flyway.clean();
		flyway.migrate();
		jdbcClient = JdbcClient.create(dataSource);
		transactionTemplate = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(
			dataSource
		));
	}

	private DataSource dataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName(POSTGRES.getDriverClassName());
		dataSource.setUrl(POSTGRES.getJdbcUrl());
		dataSource.setUsername(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		return dataSource;
	}

	protected long tradeCount() {
		return jdbcClient.sql("SELECT count(*) FROM trade")
			.query(Long.class)
			.single();
	}

	protected long rawCount() {
		return jdbcClient.sql("SELECT count(*) FROM raw_trade_ingest")
			.query(Long.class)
			.single();
	}

	protected void seedComplex() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type)
			VALUES (1, '1168010300', 'Sample-dong', 'eup-myeon-dong')
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 1, '1168010300101400001', 'Sample address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (id, parcel_id, complex_pk, apt_seq, name, unit_cnt)
			VALUES (501, 1001, 'COMPLEX-PK-501', 'APT-501', 'Sample Apartment', 740)
			""").update();
	}

	protected void seedMvpExplorationData() {
		jdbcClient.sql("""
			INSERT INTO region (id, code, name, region_type, center_lat, center_lng)
			VALUES (1, '11', 'Seoul', 'si-do', 37.5663, 126.9780)
			""").update();
		jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng)
			VALUES (11, 1, '11680', 'Gangnam-gu', 'si-gun-gu', 37.5172, 127.0473)
			""").update();
		jdbcClient.sql("""
			INSERT INTO region (id, parent_id, code, name, region_type, center_lat, center_lng)
			VALUES (111, 11, '11680103', 'Sample-dong', 'eup-myeon-dong', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO parcel (id, region_id, pnu, address, latitude, longitude)
			VALUES (1001, 111, '1168010300101400001', 'Sample address', 37.5123, 127.0456)
			""").update();
		jdbcClient.sql("""
			INSERT INTO complex (
			    id,
			    parcel_id,
			    complex_pk,
			    apt_seq,
			    name,
			    trade_name,
			    dong_cnt,
			    unit_cnt,
			    plat_area,
			    arch_area,
			    tot_area,
			    bc_rat,
			    vl_rat,
			    use_date
			)
			VALUES (
			    501,
			    1001,
			    'COMPLEX-PK-501',
			    'APT-501',
			    'Sample Apartment',
			    'Sample trade name',
			    8,
			    740,
			    12345.67,
			    2345.67,
			    98765.43,
			    22.50,
			    199.80,
			    DATE '2015-03-20'
			)
			""").update();
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
			    status,
			    processed_at
			)
			VALUES
			    (90001, 'RTMS', 'sample-rtms-20251201', '11680', '202512', 1, '{}', 'hash-1', 'NORMALIZED', now()),
			    (90002, 'RTMS', 'sample-rtms-20251215', '11680', '202512', 1, '{}', 'hash-2', 'NORMALIZED', now())
			""").update();
		jdbcClient.sql("""
			INSERT INTO trade (
			    id,
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
			VALUES
			    (9001, 501, DATE '2025-12-01', 125000, 12, 84.93, '101', 'RTMS', 'sample-rtms-20251201', 'COMPLEX-PK-501', 'APT-501', 90001),
			    (9002, 501, DATE '2025-12-15', 130000, 15, 84.93, '101', 'RTMS', 'sample-rtms-20251215', 'COMPLEX-PK-501', 'APT-501', 90002)
			""").update();
	}
}
