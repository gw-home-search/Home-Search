package com.home.infrastructure.persistence.coordinate;

import com.home.application.coordinate.readiness.ComplexCoordinateReadinessResult;
import com.home.application.coordinate.readiness.ComplexCoordinateReadinessService;
import com.home.infrastructure.ApplicationRunnerOrders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

class ComplexCoordinateReadinessRunner implements ApplicationRunner, Ordered {

	private static final Logger log = LoggerFactory.getLogger(ComplexCoordinateReadinessRunner.class);

	private final ComplexCoordinateReadinessService readinessService;
	private final int stageLimit;
	private final int resolveLimit;
	private final int projectLimit;

	ComplexCoordinateReadinessRunner(
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

	@Override
	public void run(ApplicationArguments args) {
		ComplexCoordinateReadinessResult result = readinessService.prepare(stageLimit, resolveLimit, projectLimit);
		log.info(
			"complex coordinate readiness completed staged={} pending={} skipped={} resolved={} ambiguous={} unavailable={} failed={} retried={} projectedBuildingFootprint={} projectedParcelFallback={} projectionSkipped={} projectionMissing={}",
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

	@Override
	public int getOrder() {
		return ApplicationRunnerOrders.COORDINATE_READINESS;
	}
}
