package com.home.infrastructure.persistence.ingest.normalization;

import java.time.Clock;
import java.time.Year;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public class TradePartitionMaintenanceRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TradePartitionMaintenanceRunner.class);

	private final JdbcTradePartitionMaintenanceRepository repository;
	private final Clock clock;
	private final int yearsAhead;

	public TradePartitionMaintenanceRunner(
		JdbcTradePartitionMaintenanceRepository repository,
		Clock clock,
		int yearsAhead
	) {
		this.repository = Objects.requireNonNull(repository);
		this.clock = Objects.requireNonNull(clock);
		this.yearsAhead = Math.max(0, yearsAhead);
	}

	@Override
	public void run(ApplicationArguments args) {
		int currentYear = Year.now(clock).getValue();
		int endYear = currentYear + yearsAhead;
		repository.ensureYearlyPartitions(currentYear, endYear);
		log.info("trade partition maintenance completed startYear={} endYear={}", currentYear, endYear);
	}
}
