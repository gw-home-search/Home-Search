package com.home.infrastructure.external.naver;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class NaverNewsDailyPipelineScheduler {

	private static final Logger log = LoggerFactory.getLogger(NaverNewsDailyPipelineScheduler.class);

	private final NaverNewsDailyPipelineRunner runner;
	private final AtomicBoolean running = new AtomicBoolean(false);

	NaverNewsDailyPipelineScheduler(NaverNewsDailyPipelineRunner runner) {
		this.runner = runner;
	}

	@Scheduled(
		cron = "${home.news.pipeline.daily.cron:0 0 4 * * *}",
		zone = "${home.news.pipeline.daily.zone:Asia/Seoul}"
	)
	void runDue() {
		if (!running.compareAndSet(false, true)) {
			log.warn("Naver News daily pipeline skipped because a previous run is still active");
			return;
		}
		try {
			runner.runOnce();
		}
		finally {
			running.set(false);
		}
	}
}
