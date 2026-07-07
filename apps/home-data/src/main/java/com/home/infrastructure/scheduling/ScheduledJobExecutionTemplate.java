package com.home.infrastructure.scheduling;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ScheduledJobExecutionTemplate {

	private static final Logger log = LoggerFactory.getLogger(ScheduledJobExecutionTemplate.class);

	private final String jobName;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public ScheduledJobExecutionTemplate(String jobName) {
		this.jobName = Objects.requireNonNull(jobName, "jobName must not be null");
	}

	public boolean execute(Runnable task) {
		Objects.requireNonNull(task, "task must not be null");
		if (!running.compareAndSet(false, true)) {
			log.warn("{} skipped because a previous run is still active", jobName);
			return false;
		}
		try {
			task.run();
			return true;
		}
		finally {
			running.set(false);
		}
	}
}
