package com.home.migration;

import org.springframework.boot.ExitCodeGenerator;

class MigrationUsageException extends RuntimeException implements ExitCodeGenerator {

	private static final int EXIT_CODE = 2;

	MigrationUsageException(String message) {
		super(message);
	}

	@Override
	public int getExitCode() {
		return EXIT_CODE;
	}
}
