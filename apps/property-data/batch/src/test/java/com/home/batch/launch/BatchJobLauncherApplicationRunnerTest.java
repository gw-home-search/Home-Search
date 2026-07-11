package com.home.batch.launch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;

class BatchJobLauncherApplicationRunnerTest {

	@Test
	@DisplayName("launcher는 선택된 job을 실행하고 COMPLETED exit status를 성공으로 처리한다")
	void launcherRunsSelectedJob() throws Exception {
		JobLauncher jobLauncher = mock(JobLauncher.class);
		Job job = mock(Job.class);
		BatchMetadataSchemaPreflight preflight = mock(BatchMetadataSchemaPreflight.class);
		BatchExecutionCorrelationGuard correlationGuard = mock(BatchExecutionCorrelationGuard.class);
		Environment environment = mock(Environment.class);
		when(job.getName()).thenReturn("rtmsDailyRefreshJob");
		when(environment.getProperty("SPRING_BATCH_JOB_NAME")).thenReturn("rtmsDailyRefreshJob");
		JobExecution execution = new JobExecution(1L, new JobParameters());
		execution.setStatus(BatchStatus.COMPLETED);
		execution.setExitStatus(ExitStatus.COMPLETED);
		when(jobLauncher.run(org.mockito.ArgumentMatchers.eq(job), org.mockito.ArgumentMatchers.any(JobParameters.class)))
			.thenReturn(execution);
		BatchJobLauncherApplicationRunner runner = new BatchJobLauncherApplicationRunner(
			jobLauncher,
			List.of(job),
			preflight,
			correlationGuard,
			environment,
			Clock.systemUTC(),
			new BatchExitCodeExceptionMapper()
		);

		runner.run(new DefaultApplicationArguments(
			"runDate=2026-07-07",
			"requestId=123e4567-e89b-12d3-a456-426614174020"
		));

		verify(preflight).verify();
		verify(correlationGuard).lock("123e4567-e89b-12d3-a456-426614174020");
		verify(correlationGuard).verify(
			org.mockito.ArgumentMatchers.eq("rtmsDailyRefreshJob"),
			org.mockito.ArgumentMatchers.any(JobParameters.class)
		);
		verify(jobLauncher).run(org.mockito.ArgumentMatchers.eq(job), org.mockito.ArgumentMatchers.any(JobParameters.class));
	}

	@Test
	@DisplayName("launcher는 warning exit status를 exit code 1 예외로 매핑한다")
	void launcherMapsWarningExitStatusToNonZero() throws Exception {
		JobLauncher jobLauncher = mock(JobLauncher.class);
		Job job = mock(Job.class);
		BatchMetadataSchemaPreflight preflight = mock(BatchMetadataSchemaPreflight.class);
		BatchExecutionCorrelationGuard correlationGuard = mock(BatchExecutionCorrelationGuard.class);
		Environment environment = mock(Environment.class);
		when(job.getName()).thenReturn("rtmsDailyRefreshJob");
		when(environment.getProperty("SPRING_BATCH_JOB_NAME")).thenReturn("rtmsDailyRefreshJob");
		JobExecution execution = new JobExecution(1L, new JobParameters());
		execution.setExitStatus(new ExitStatus("COMPLETED_WITH_WARNINGS"));
		when(jobLauncher.run(org.mockito.ArgumentMatchers.eq(job), org.mockito.ArgumentMatchers.any(JobParameters.class)))
			.thenReturn(execution);
		BatchJobLauncherApplicationRunner runner = new BatchJobLauncherApplicationRunner(
			jobLauncher,
			List.of(job),
			preflight,
			correlationGuard,
			environment,
			Clock.systemUTC(),
			new BatchExitCodeExceptionMapper()
		);

		assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(
			"runDate=2026-07-07",
			"requestId=123e4567-e89b-12d3-a456-426614174021"
		)))
			.isInstanceOf(BatchExitCodeException.class)
			.extracting("exitCode")
			.isEqualTo(1);
	}
}
