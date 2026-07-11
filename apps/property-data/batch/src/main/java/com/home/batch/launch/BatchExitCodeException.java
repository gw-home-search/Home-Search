package com.home.batch.launch;

import org.springframework.boot.ExitCodeGenerator;

public class BatchExitCodeException extends RuntimeException implements ExitCodeGenerator {

	private final int exitCode;

	BatchExitCodeException(String message, int exitCode) {
		super(message);
		this.exitCode = exitCode;
	}

	@Override
	public int getExitCode() {
		return exitCode;
	}
}
