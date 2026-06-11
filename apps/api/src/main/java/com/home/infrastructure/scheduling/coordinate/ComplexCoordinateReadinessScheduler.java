package com.home.infrastructure.scheduling.coordinate;

import com.home.application.coordinate.readiness.ComplexCoordinateReadinessResult;
import com.home.application.coordinate.readiness.ComplexCoordinateReadinessService;
import com.home.infrastructure.scheduling.ScheduledJobExecutionTemplate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class ComplexCoordinateReadinessScheduler {

	private static final Logger log = LoggerFactory.getLogger(ComplexCoordinateReadinessScheduler.class);

	private final ComplexCoordinateReadinessService readinessService;
	private final int stageLimit;
	private final int resolveLimit;
	private final int projectLimit;
	private final ScheduledJobExecutionTemplate execution =
		new ScheduledJobExecutionTemplate("complex coordinate readiness");

	public ComplexCoordinateReadinessScheduler(
		ComplexCoordinateReadinessService readinessService,
		int stageLimit,
		int resolveLimit,
		int projectLimit
	) {
		this.readinessService = readinessService;
		this.stageLimit = stageLimit;
		this.resolveLimit = resolveLimit;
		this.projectLimit = projectLimit;
	}

	@Scheduled(
		initialDelayString = "${home.coordinate.readiness.scheduler.initial-delay-millis:60000}",
		fixedDelayString = "${home.coordinate.readiness.scheduler.fixed-delay-millis:3600000}"
	)
	void runDue() {
		execution.execute(this::runScheduledExecution);
	}

	private void runScheduledExecution() {
		ComplexCoordinateReadinessResult result = readinessService.prepare(stageLimit, resolveLimit, projectLimit);
		log.info(
			"complex coordinate readiness scheduled run completed staged={} pending={} skipped={} resolved={} ambiguous={} unavailable={} failed={} retried={} projectedBuildingFootprint={} projectedParcelFallback={} projectionSkipped={} projectionMissing={}",
			result.staged(),
			result.pending(),
			result.skipped(),
			result.resolved(),
			result.ambiguous(),
			result.unavailable(),
			result.failed(),
			result.retried(),
			result.projectedBuildingFootprint(),
			result.projectedParcelFallback(),
			result.projectionSkipped(),
			result.projectionMissing()
		);
	}
}
