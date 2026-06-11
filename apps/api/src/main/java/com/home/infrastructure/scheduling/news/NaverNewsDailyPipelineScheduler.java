package com.home.infrastructure.scheduling.news;

import com.home.infrastructure.scheduling.ScheduledJobExecutionTemplate;

import org.springframework.scheduling.annotation.Scheduled;

public class NaverNewsDailyPipelineScheduler {

	private final NaverNewsDailyPipelineRunner runner;
	private final ScheduledJobExecutionTemplate execution =
		new ScheduledJobExecutionTemplate("Naver News daily pipeline");

	NaverNewsDailyPipelineScheduler(NaverNewsDailyPipelineRunner runner) {
		this.runner = runner;
	}

	@Scheduled(
		cron = "${home.news.pipeline.daily.cron:0 0 4 * * *}",
		zone = "${home.news.pipeline.daily.zone:Asia/Seoul}"
	)
	void runDue() {
		execution.execute(runner::runOnce);
	}
}
