package com.home.migration;

enum MigrationOperation {
	INFO("info"),
	VALIDATE("validate"),
	MIGRATE("migrate"),
	REPAIR_MISSING_V3("repair-missing-v3"),
	BACKFILL_REGISTRY_TRADE_DATE("backfill-registry-trade-date");

	private final String argument;

	MigrationOperation(String argument) {
		this.argument = argument;
	}

	static MigrationOperation from(String value) {
		for (MigrationOperation operation : values()) {
			if (operation.argument.equals(value)) {
				return operation;
			}
		}
		throw new MigrationUsageException("Unsupported operation: " + value);
	}
}
