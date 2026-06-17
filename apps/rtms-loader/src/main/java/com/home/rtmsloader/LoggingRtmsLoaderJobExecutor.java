package com.home.rtmsloader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LoggingRtmsLoaderJobExecutor implements RtmsLoaderJobExecutor {

	private static final Logger log = LoggerFactory.getLogger(LoggingRtmsLoaderJobExecutor.class);

	@Override
	public RtmsLoaderJobExecution execute(RtmsLoaderJobPlan plan) {
		log.info(
			"RTMS loader plan accepted mode={} monthCount={}",
			plan.mode().propertyValue(),
			plan.months().size()
		);
		return new RtmsLoaderJobExecution(plan.mode(), plan.months().size());
	}
}
