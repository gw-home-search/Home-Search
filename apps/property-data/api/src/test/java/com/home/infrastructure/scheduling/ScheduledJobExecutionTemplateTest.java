package com.home.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScheduledJobExecutionTemplateTest {

	@Test
	@DisplayName("scheduled job execution template은 실행 중 재진입을 거부하고 완료 후 다음 실행을 허용한다")
	void executionTemplateRejectsConcurrentReentryAndAllowsNextRun() throws InterruptedException {
		ScheduledJobExecutionTemplate template = new ScheduledJobExecutionTemplate("test job");
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger executions = new AtomicInteger();
		Thread firstRun = new Thread(() -> template.execute(() -> {
			executions.incrementAndGet();
			started.countDown();
			await(release);
		}));

		firstRun.start();
		assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

		assertThat(template.execute(executions::incrementAndGet)).isFalse();
		release.countDown();
		firstRun.join(1_000);

		assertThat(template.execute(executions::incrementAndGet)).isTrue();
		assertThat(executions).hasValue(2);
	}

	@Test
	@DisplayName("scheduled job execution template은 작업 예외 후에도 실행 가드를 해제한다")
	void executionTemplateReleasesGuardAfterFailure() {
		ScheduledJobExecutionTemplate template = new ScheduledJobExecutionTemplate("test job");
		AtomicInteger executions = new AtomicInteger();

		assertThatThrownBy(() -> template.execute(() -> {
			throw new IllegalStateException("failed");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(template.execute(executions::incrementAndGet)).isTrue();
		assertThat(executions).hasValue(1);
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting", exception);
		}
	}
}
