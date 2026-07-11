package com.home.batch.rtms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.home.infrastructure.ops.notification.OpsNotification;
import com.home.infrastructure.ops.notification.OpsNotifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;

class RtmsBatchSummaryListenerTest {

	@Test
	@DisplayName("summary listener는 warning flag를 COMPLETED_WITH_WARNINGS로 반영하고 알림을 보낸다")
	void listenerMapsWarningsAndSendsNotification() {
		List<OpsNotification> notifications = new ArrayList<>();
		RtmsBatchSummaryListener listener = new RtmsBatchSummaryListener(notifications::add);
		JobExecution execution = new JobExecution(new JobInstance(1L, "rtmsDailyRefreshJob"), 2L, null);
		execution.setExitStatus(ExitStatus.COMPLETED);
		execution.getExecutionContext().put(RtmsBatchExecutionSummary.WARNINGS_CONTEXT_KEY, true);

		listener.afterJob(execution);

		assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED_WITH_WARNINGS");
		assertThat(notifications).singleElement()
			.satisfies(notification -> assertThat(notification.eventType()).isEqualTo("rtms-batch"));
	}

	@Test
	@DisplayName("summary listener는 알림 실패를 job 실패로 전파하지 않는다")
	void listenerDoesNotPropagateNotificationFailure() {
		OpsNotifier failingNotifier = notification -> {
			throw new IllegalStateException("notify failed");
		};
		RtmsBatchSummaryListener listener = new RtmsBatchSummaryListener(failingNotifier);
		JobExecution execution = new JobExecution(new JobInstance(1L, "rtmsDailyRefreshJob"), 2L, null);
		execution.setExitStatus(ExitStatus.COMPLETED);

		listener.afterJob(execution);

		assertThat(execution.getExitStatus()).isEqualTo(ExitStatus.COMPLETED);
	}
}
