package com.home.infrastructure.persistence.ingest.normalization;

import java.time.Clock;
import java.time.Year;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public class TradePartitionMaintenanceRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TradePartitionMaintenanceRunner.class);

	private final Supplier<JdbcTradePartitionMaintenanceRepository> repositorySupplier;
	private final Clock clock;
	private final int yearsAhead;
	private final BooleanSupplier databaseAvailable;

	public TradePartitionMaintenanceRunner(
		JdbcTradePartitionMaintenanceRepository repository,
		Clock clock,
		int yearsAhead
	) {
		this(() -> repository, clock, yearsAhead, () -> true);
	}

	public TradePartitionMaintenanceRunner(
		Supplier<JdbcTradePartitionMaintenanceRepository> repositorySupplier,
		Clock clock,
		int yearsAhead,
		BooleanSupplier databaseAvailable
	) {
		this.repositorySupplier = Objects.requireNonNull(repositorySupplier);
		this.clock = Objects.requireNonNull(clock);
		this.yearsAhead = Math.max(0, yearsAhead);
		this.databaseAvailable = Objects.requireNonNull(databaseAvailable);
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!databaseAvailable.getAsBoolean()) {
			log.warn("trade partition maintenance skipped because JdbcClient is unavailable");
			return;
		}
		int currentYear = Year.now(clock).getValue();
		int endYear = currentYear + yearsAhead;
		repositorySupplier.get().ensureYearlyPartitions(currentYear, endYear);
		log.info("trade partition maintenance completed startYear={} endYear={}", currentYear, endYear);
	}
}
