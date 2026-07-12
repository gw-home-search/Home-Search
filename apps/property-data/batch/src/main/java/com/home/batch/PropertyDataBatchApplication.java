package com.home.batch;

import java.util.Set;

import com.home.infrastructure.batch.PropertyDataBatchCoreConfiguration;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(PropertyDataBatchCoreConfiguration.class)
public class PropertyDataBatchApplication {

	private static final Set<String> SUPPORTED_JOB_NAMES = Set.of(
		"rtmsDailyRefreshJob", "rtmsBackfillJob", "complexBuildingMetadataJob", "complexOdcMetadataGapFillJob"
	);

	public static void main(String[] args) {
		String jobName = jobName();
		if (jobName == null || jobName.isBlank()) {
			System.err.println("Missing required environment variable: SPRING_BATCH_JOB_NAME");
			System.exit(2);
			return;
		}
		if (!SUPPORTED_JOB_NAMES.contains(jobName)) {
			System.err.println("Unknown batch job: " + jobName);
			System.exit(2);
			return;
		}
		ConfigurableApplicationContext context = null;
		int exitCode;
		try {
			context = SpringApplication.run(PropertyDataBatchApplication.class, args);
			exitCode = SpringApplication.exit(context);
		}
		catch (RuntimeException exception) {
			exitCode = exitCodeFor(exception);
			if (context != null) {
				int failureExitCode = exitCode;
				exitCode = SpringApplication.exit(context, () -> failureExitCode);
			}
		}
		System.exit(exitCode);
	}

	private static String jobName() {
		String jobName = System.getenv("SPRING_BATCH_JOB_NAME");
		if (jobName == null || jobName.isBlank()) {
			jobName = System.getProperty("SPRING_BATCH_JOB_NAME");
		}
		return jobName;
	}

	private static int exitCodeFor(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof ExitCodeGenerator exitCodeGenerator) {
				return exitCodeGenerator.getExitCode();
			}
			for (Throwable suppressed : current.getSuppressed()) {
				int exitCode = exitCodeFor(suppressed);
				if (exitCode != 1) {
					return exitCode;
				}
			}
		}
		return 1;
	}
}
