package com.home.infrastructure.persistence.ingest.raw;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.home.application.ingest.reconciliation.RawIngestReconciliationResult;
import com.home.application.ingest.reconciliation.RawIngestReconciliationService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

public class RawIngestReconciliationRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(RawIngestReconciliationRunner.class);

	private final Supplier<RawIngestReconciliationService> serviceSupplier;
	private final int batchSize;
	private final BooleanSupplier databaseAvailable;

	public RawIngestReconciliationRunner(RawIngestReconciliationService service, int batchSize) {
		this(() -> service, batchSize, () -> true);
	}

	public RawIngestReconciliationRunner(
		Supplier<RawIngestReconciliationService> serviceSupplier,
		int batchSize,
		BooleanSupplier databaseAvailable
	) {
		this.serviceSupplier = Objects.requireNonNull(serviceSupplier);
		this.batchSize = batchSize;
		this.databaseAvailable = Objects.requireNonNull(databaseAvailable);
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!databaseAvailable.getAsBoolean()) {
			log.warn("raw ingest reconciliation skipped because JdbcClient is unavailable");
			return;
		}
		RawIngestReconciliationResult result = serviceSupplier.get().reconcileReceived(batchSize);
		log.info("raw ingest reconciliation completed processed={} normalized={}", result.processed(), result.normalized());
	}

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.RAW_INGEST_RECONCILIATION;
	}
}
