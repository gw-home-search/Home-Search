package com.home.migration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

record MigrationOperationRequest(
	MigrationOperation operation,
	String target,
	String confirm,
	String confirmDatabase,
	int batchSize,
	long sleepMillis
) {

	private static final int DEFAULT_BATCH_SIZE = 20_000;
	private static final long DEFAULT_SLEEP_MILLIS = 100;
	private static final Set<String> KNOWN_ARGUMENTS = Set.of(
		"operation", "target", "confirm", "confirm-database", "batch-size", "sleep-millis"
	);

	static MigrationOperationRequest parse(String[] args) {
		Map<String, String> values = arguments(args);
		MigrationOperation operation = MigrationOperation.from(required(values, "operation"));
		String target = values.get("target");
		String confirm = values.get("confirm");
		String confirmDatabase = values.get("confirm-database");
		int batchSize = integer(values.get("batch-size"), DEFAULT_BATCH_SIZE, "batch-size", 1, 100_000);
		long sleepMillis = longValue(values.get("sleep-millis"), DEFAULT_SLEEP_MILLIS, "sleep-millis", 0, 60_000);

		switch (operation) {
			case INFO, VALIDATE -> requireAbsent(values, "target", "confirm", "confirm-database", "batch-size", "sleep-millis");
			case MIGRATE -> validateMigrate(target, confirm, confirmDatabase, values);
			case REPAIR_MISSING_V3 -> validateRepair(target, confirm, confirmDatabase, values);
			case BACKFILL_REGISTRY_TRADE_DATE -> {
				requireAbsent(values, "target", "confirm");
				requireHomeSearchConfirmation(confirmDatabase);
			}
		}
		return new MigrationOperationRequest(operation, target, confirm, confirmDatabase, batchSize, sleepMillis);
	}

	private static Map<String, String> arguments(String[] args) {
		Map<String, String> values = new LinkedHashMap<>();
		if (args == null) {
			return values;
		}
		for (String raw : args) {
			String argument = raw == null ? "" : raw.trim();
			if (argument.startsWith("--")) {
				argument = argument.substring(2);
			}
			int separator = argument.indexOf('=');
			if (separator <= 0 || separator == argument.length() - 1) {
				throw new MigrationUsageException("Arguments must use --name=value: " + safeArgument(raw));
			}
			String name = argument.substring(0, separator);
			String value = argument.substring(separator + 1).trim();
			if (!KNOWN_ARGUMENTS.contains(name)) {
				throw new MigrationUsageException("Unknown argument: " + name);
			}
			if (values.putIfAbsent(name, value) != null) {
				throw new MigrationUsageException("Duplicate argument: " + name);
			}
		}
		return values;
	}

	private static void validateMigrate(String target, String confirm, String confirmDatabase, Map<String, String> values) {
		requireAbsent(values, "batch-size", "sleep-millis");
		requireHomeSearchConfirmation(confirmDatabase);
		if (target == null || !(target.equals("latest") || target.matches("[1-9][0-9]*"))) {
			throw new MigrationUsageException("migrate target must be a positive version or latest");
		}
		if (confirm == null || !confirm.matches("[1-9][0-9]*")) {
			throw new MigrationUsageException("migrate requires a numeric --confirm");
		}
		if (!target.equals("latest") && !target.equals(confirm)) {
			throw new MigrationUsageException("migrate --confirm must match --target");
		}
	}

	private static void validateRepair(String target, String confirm, String confirmDatabase, Map<String, String> values) {
		requireAbsent(values, "target", "batch-size", "sleep-millis");
		requireHomeSearchConfirmation(confirmDatabase);
		if (!"3".equals(confirm)) {
			throw new MigrationUsageException("repair-missing-v3 requires --confirm=3");
		}
	}

	private static void requireHomeSearchConfirmation(String confirmDatabase) {
		if (!"home_search".equals(confirmDatabase)) {
			throw new MigrationUsageException("Mutating operations require --confirm-database=home_search");
		}
	}

	private static String required(Map<String, String> values, String name) {
		String value = values.get(name);
		if (value == null || value.isBlank()) {
			throw new MigrationUsageException("Missing required argument: --" + name);
		}
		return value;
	}

	private static void requireAbsent(Map<String, String> values, String... names) {
		for (String name : names) {
			if (values.containsKey(name)) {
				throw new MigrationUsageException("Argument is not supported for this operation: --" + name);
			}
		}
	}

	private static int integer(String value, int defaultValue, String name, int min, int max) {
		long parsed = longValue(value, defaultValue, name, min, max);
		return Math.toIntExact(parsed);
	}

	private static long longValue(String value, long defaultValue, String name, long min, long max) {
		if (value == null) {
			return defaultValue;
		}
		try {
			long parsed = Long.parseLong(value);
			if (parsed < min || parsed > max) {
				throw new MigrationUsageException(name + " must be between " + min + " and " + max);
			}
			return parsed;
		}
		catch (NumberFormatException exception) {
			throw new MigrationUsageException(name + " must be an integer");
		}
	}

	private static String safeArgument(String value) {
		if (value == null) {
			return "<null>";
		}
		int separator = value.indexOf('=');
		return separator < 0 ? value : value.substring(0, separator + 1) + "[REDACTED]";
	}
}
