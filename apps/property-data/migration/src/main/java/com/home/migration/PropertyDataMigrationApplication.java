package com.home.migration;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@Import(MigrationCommandRunner.class)
public class PropertyDataMigrationApplication {

	public static void main(String[] args) {
		try {
			MigrationOperationRequest.parse(args);
		}
		catch (MigrationUsageException exception) {
			System.err.println(exception.getMessage());
			System.exit(exception.getExitCode());
			return;
		}

		ConfigurableApplicationContext context = null;
		int exitCode = 0;
		try {
			context = SpringApplication.run(PropertyDataMigrationApplication.class, args);
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

	private static int exitCodeFor(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof ExitCodeGenerator generator) {
				return generator.getExitCode();
			}
		}
		return 1;
	}
}
