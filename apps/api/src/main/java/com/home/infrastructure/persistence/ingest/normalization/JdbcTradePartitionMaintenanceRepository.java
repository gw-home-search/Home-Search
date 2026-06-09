package com.home.infrastructure.persistence.ingest.normalization;

import java.time.LocalDate;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcTradePartitionMaintenanceRepository {

	private static final int MIN_SUPPORTED_YEAR = 1900;
	private static final int MAX_SUPPORTED_YEAR = 9999;

	private final JdbcClient jdbcClient;

	public JdbcTradePartitionMaintenanceRepository(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient);
	}

	public void ensureYearlyPartitions(int startYear, int endYear) {
		if (startYear > endYear) {
			return;
		}
		validateYear(startYear);
		validateYear(endYear);
		for (int year = startYear; year <= endYear; year++) {
			ensureYearlyPartition(year);
		}
	}

	private void ensureYearlyPartition(int year) {
		LocalDate from = LocalDate.of(year, 1, 1);
		LocalDate to = LocalDate.of(year + 1, 1, 1);
		jdbcClient.sql("""
			CREATE TABLE IF NOT EXISTS %s
			PARTITION OF trade
			FOR VALUES FROM (DATE '%s') TO (DATE '%s')
			""".formatted(partitionName(year), from, to))
			.update();
	}

	private String partitionName(int year) {
		return "trade_" + year;
	}

	private void validateYear(int year) {
		if (year < MIN_SUPPORTED_YEAR || year > MAX_SUPPORTED_YEAR - 1) {
			throw new IllegalArgumentException("unsupported trade partition year: " + year);
		}
	}
}
