package com.home.batch.rtms;

import com.home.infrastructure.ops.notification.OpsNotification;
import com.home.infrastructure.ops.notification.OpsNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

public class RtmsBatchSummaryListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(RtmsBatchSummaryListener.class);

    private final OpsNotifier notifier;

    public RtmsBatchSummaryListener(OpsNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        boolean warnings = Boolean.TRUE.equals(
                jobExecution.getExecutionContext().get(RtmsBatchExecutionSummary.WARNINGS_CONTEXT_KEY));
        if (warnings) {
            jobExecution.setExitStatus(new ExitStatus("COMPLETED_WITH_WARNINGS"));
        }
        try {
            notifier.send(new OpsNotification(
                    "rtms-batch",
                    jobExecution.getJobInstance().getJobName(),
                    "exitStatus=" + jobExecution.getExitStatus().getExitCode()));
        } catch (RuntimeException exception) {
            log.warn(
                    "RTMS batch ops notification failed status={}",
                    jobExecution.getExitStatus().getExitCode());
        }
    }
}
