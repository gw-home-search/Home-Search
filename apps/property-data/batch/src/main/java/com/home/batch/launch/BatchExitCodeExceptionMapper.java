package com.home.batch.launch;

import org.springframework.batch.core.ExitStatus;

public class BatchExitCodeExceptionMapper {

	public static final int FAILED_JOB_EXIT_CODE = 1;
	public static final int INVALID_ARGUMENT_EXIT_CODE = 2;

	BatchExitCodeException invalidArgument(String message) {
		return new BatchExitCodeException(message, INVALID_ARGUMENT_EXIT_CODE);
	}

	BatchExitCodeException failedJob(String message) {
		return new BatchExitCodeException(message, FAILED_JOB_EXIT_CODE);
	}

	boolean successful(ExitStatus exitStatus) {
		return ExitStatus.COMPLETED.getExitCode().equals(exitStatus.getExitCode());
	}
}
