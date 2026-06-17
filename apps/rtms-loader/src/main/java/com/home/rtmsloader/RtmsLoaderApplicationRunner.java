package com.home.rtmsloader;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class RtmsLoaderApplicationRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(RtmsLoaderApplicationRunner.class);

	private final RtmsLoaderProperties properties;
	private final RtmsLoaderJobPlanner planner;
	private final RtmsLoaderJobExecutor executor;
	private final Clock clock;

	RtmsLoaderApplicationRunner(
		RtmsLoaderProperties properties,
		RtmsLoaderJobPlanner planner,
		RtmsLoaderJobExecutor executor
	) {
		this(properties, planner, executor, Clock.systemUTC());
	}

	RtmsLoaderApplicationRunner(
		RtmsLoaderProperties properties,
		RtmsLoaderJobPlanner planner,
		RtmsLoaderJobExecutor executor,
		Clock clock
	) {
		this.properties = properties;
		this.planner = planner;
		this.executor = executor;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!properties.isEnabled()) {
			log.info("RTMS loader is disabled");
			return;
		}
		RtmsLoaderJobPlan plan = planner.plan(properties.toRequest(clock));
		RtmsLoaderJobExecution execution = executor.execute(plan);
		log.info(
			"RTMS loader execution completed mode={} plannedMonthCount={}",
			execution.mode().propertyValue(),
			execution.plannedMonthCount()
		);
	}
}
