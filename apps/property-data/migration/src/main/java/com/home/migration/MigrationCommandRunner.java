package com.home.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.RepairResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class MigrationCommandRunner implements ApplicationRunner {

	private static final String LOCATION = "classpath:db/migration/api";

	private final DataSource dataSource;

	MigrationCommandRunner(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		MigrationOperationRequest request = MigrationOperationRequest.parse(arguments.getSourceArgs());
		try {
			switch (request.operation()) {
				case INFO -> info(flyway(null));
				case VALIDATE -> validate(flyway(null));
				case MIGRATE -> migrate(request);
				case REPAIR_MISSING_V3 -> repairMissingV3();
				case BACKFILL_REGISTRY_TRADE_DATE -> backfill(request);
			}
		}
		catch (MigrationUsageException | MigrationOperationException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new MigrationOperationException("Migration operation failed: " + exception.getClass().getSimpleName(), exception);
		}
	}

	private void info(Flyway flyway) {
		for (MigrationInfo migration : flyway.info().all()) {
			System.out.printf("version=%s state=%s description=%s%n",
				version(migration), migration.getState().getDisplayName(), migration.getDescription());
		}
	}

	private void validate(Flyway flyway) {
		ValidateResult result = flyway.validateWithResult();
		if (!result.validationSuccessful) {
			throw new MigrationOperationException("Flyway validation failed: " + result.getAllErrorMessages());
		}
		System.out.printf("validationSuccessful=true validateCount=%d%n", result.validateCount);
	}

	private void migrate(MigrationOperationRequest request) {
		MigrationVersion target = request.target().equals("latest")
			? null
			: MigrationVersion.fromVersion(request.target());
		Flyway flyway = flyway(target);
		List<MigrationInfo> pending = Arrays.stream(flyway.info().pending()).toList();
		if (pending.isEmpty()) {
			System.out.println("pending=[]; no migration executed");
			return;
		}
		String highestPending = version(pending.get(pending.size() - 1));
		System.out.println("pending=" + pending.stream().map(MigrationCommandRunner::version).toList());
		if (!highestPending.equals(request.confirm())) {
			throw new MigrationUsageException("--confirm must match highest pending version: " + highestPending);
		}
		if (target != null && !highestPending.equals(request.target())) {
			throw new MigrationOperationException("Requested target is not pending: " + request.target());
		}
		var result = flyway.migrate();
		System.out.printf("migrationsExecuted=%d target=%s%n", result.migrationsExecuted, highestPending);
	}

	private void repairMissingV3() {
		requireRepairBackups();
		Flyway flyway = flyway(null);
		MigrationInfo[] migrations = flyway.info().all();
		long failed = Arrays.stream(migrations).filter(info -> info.getState().isFailed()).count();
		if (failed != 0) {
			throw new MigrationOperationException("Repair preflight failed: failed migration count=" + failed);
		}
		FlywayRepairPreflight.Decision decision = FlywayRepairPreflight.verify(migrations);
		RepairResult result = flyway.repair();
		boolean expected = result.migrationsDeleted.size() == 1
			&& "3".equals(result.migrationsDeleted.get(0).version)
			&& (decision.alignV1()
				? result.migrationsAligned.size() == 1 && "1".equals(result.migrationsAligned.get(0).version)
				: result.migrationsAligned.isEmpty())
			&& result.migrationsRemoved.isEmpty();
		if (!expected) {
			throw new MigrationOperationException("Unexpected Flyway repair actions; stop before V5/V6");
		}
		validate(flyway(null, true));
		System.out.printf("migrationsDeleted=[3] migrationsAligned=%s migrationsRemoved=[]%n",
			decision.alignV1() ? "[1]" : "[]");
	}

	private void requireRepairBackups() {
		Path historyCsv = requiredBackup("MIGRATION_HISTORY_CSV_BACKUP_FILE");
		Path historySql = requiredBackup("MIGRATION_HISTORY_SQL_BACKUP_FILE");
		Path schema = requiredBackup("MIGRATION_SCHEMA_BACKUP_FILE");
		System.out.printf("repairBackupsVerified historyCsv=%s historySql=%s schema=%s%n",
			historyCsv.getFileName(), historySql.getFileName(), schema.getFileName());
	}

	private Path requiredBackup(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new MigrationOperationException(name + " must identify an existing backup file");
		}
		Path path = Path.of(value);
		try {
			if (!Files.isRegularFile(path) || Files.size(path) == 0) {
				throw new MigrationOperationException(name + " must identify a non-empty backup file");
			}
			return path;
		}
		catch (IOException exception) {
			throw new MigrationOperationException(name + " backup file could not be inspected", exception);
		}
	}

	private void backfill(MigrationOperationRequest request) {
		JdbcClient jdbcClient = JdbcClient.create(dataSource);
		TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		RegistryTradeDateBackfill backfill = new RegistryTradeDateBackfill(jdbcClient, transactions);
		RegistryTradeDateBackfill.Result result = backfill.execute(request.batchSize(), request.sleepMillis());
		System.out.printf("backfillComplete updated=%d batches=%d elapsedMillis=%d%n",
			result.updated(), result.batches(), result.elapsedMillis());
	}

	private Flyway flyway(MigrationVersion target) {
		return flyway(target, false);
	}

	private Flyway flyway(MigrationVersion target, boolean ignorePending) {
		var configuration = Flyway.configure()
			.dataSource(dataSource)
			.locations(LOCATION)
			.schemas("public", "reference", "batch")
			.defaultSchema("public")
			.cleanDisabled(true);
		if (ignorePending) configuration.ignoreMigrationPatterns("*:pending");
		if (target != null) {
			configuration.target(target);
		}
		return configuration.load();
	}

	private static String version(MigrationInfo migration) {
		return migration.getVersion() == null ? "repeatable" : migration.getVersion().getVersion();
	}
}
