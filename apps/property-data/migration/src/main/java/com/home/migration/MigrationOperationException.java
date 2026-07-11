package com.home.migration;

class MigrationOperationException extends RuntimeException {

	MigrationOperationException(String message) {
		super(message);
	}

	MigrationOperationException(String message, Throwable cause) {
		super(message, cause);
	}
}
